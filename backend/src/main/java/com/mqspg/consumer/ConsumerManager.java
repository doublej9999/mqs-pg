package com.mqspg.consumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.mapper.CfgRouteMapper;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.metrics.MqsMetrics;
import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsConsumerFactory;
import com.mqspg.writer.batch.BatchManager;
import com.mqspg.writer.batch.BatchProperties;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费生命周期管理（tech-design §4.3 / §41）。
 *
 * <p>V1 约束：**一个 Topic+Tag 对应一个 Consumer，不做水平扩展**（PRD §41）。
 * 因此这里用「每个路由一个虚拟线程」的模型，不引入分区分配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerManager {

    private final CfgRouteMapper routeMapper;
    private final ConfigRegistry registry;
    private final MqsConsumerFactory consumerFactory;
    private final BatchManager batchManager;
    private final BatchProperties batchProps;
    private final ConsumerStateRepository stateRepository;
    private final PgHealthGate healthGate;
    private final TargetDataSourceRegistry dataSources;
    private final MqsMetrics metrics;

    @Value("${mqs-pg.consumer.auto-start:true}")
    private boolean autoStart;

    @Value("${mqs-pg.pg-health.probe-interval:5s}")
    private Duration probeInterval;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, RouteConsumer> consumers = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoStart) {
            log.info("mqs-pg.consumer.auto-start=false，不自动启动 Consumer");
            return;
        }
        registry.refreshAll();
        refresh();
    }

    /** 为所有 ACTIVE 路由补齐 Consumer；已存在的复用。 */
    public synchronized void refresh() {
        List<CfgRoute> routes = routeMapper.selectList(
                Wrappers.<CfgRoute>lambdaQuery().eq(CfgRoute::getStatus, "ACTIVE"));

        for (CfgRoute route : routes) {
            if (route.getActiveVersion() == null) {
                log.warn("路由 {} 没有 ACTIVE 配置版本，跳过启动消费者", route.getId());
                continue;
            }
            startRoute(route);
        }
    }

    /**
     * 周期性补齐消费者（G3 后续修正）。
     *
     * <p><b>为什么必须有这个定时任务</b>：{@link #startRoute} 在「目标数据源不可用」时
     * 会把状态置为 PAUSED 并直接 return，**不提交消费者线程**。原注释写着
     * 「交给 H1 的注册表对账 + 闸门恢复去重新拉起」，但这两条路都走不通：
     * <ul>
     *   <li>H1 的 {@code reconcile()} 只在<b>内存版本与库内版本不一致</b>时才 refresh
     *       注册表，它压根不碰 ConsumerManager；</li>
     *   <li>{@code RouteConsumer#tryRecover} 是消费者<b>线程内部</b>的恢复逻辑 ——
     *       可消费者根本没被创建，就没有线程去调它。</li>
     * </ul>
     * 结果是这类路由会**永久卡在 PAUSED**，只有人工重启或改一次配置才会重试。
     * 容器编排下这几乎是必然事件：后端可能先于数据库/网络就绪，首次探测失败，
     * 然后整条链路一直不工作，而页面只显示「等待恢复后自动拉起」—— 等多久都不会恢复。
     *
     * <p>这里只是「补差」：已经存在的消费者直接跳过，不打扰；成本是一次
     * {@code SELECT ... WHERE status='ACTIVE'}，间隔默认 30s。
     */
    @Scheduled(fixedDelayString = "${mqs-pg.consumer.reconcile-interval:30s}")
    public void reconcileConsumers() {
        if (!autoStart) {
            // auto-start=false 是测试/离线模式，此时不该有后台线程主动拉消费者
            return;
        }
        try {
            refresh();
        } catch (RuntimeException e) {
            // 库暂时不可用：不要刷屏，也不要让定时任务本身挂掉
            log.warn("消费者补齐跳过: {}", e.getMessage());
        }
    }

    private void startRoute(CfgRoute route) {
        long routeId = route.getId();
        if (consumers.containsKey(routeId)) {
            return;
        }

        ConsumerSpec spec = new ConsumerSpec(
                routeId,
                route.getTopic(),
                route.getTag(),
                "mqs-pg-" + route.getName(),
                batchProps.getPullSize(),
                batchProps.getInvisibleDuration());

        MqsConsumer consumer = consumerFactory.create(spec);

        // 探测用的连接：取该路由目标库。
        //
        // 拿不到就**不提交消费者**，只把状态置 PAUSED 并留下可读原因。
        // 原来这里兜底成 `new JdbcTemplate()`（没有 DataSource）—— 那是个
        // 「必然失败的探测器」：每次 tryRecover 都抛 IllegalStateException，
        // 闸门永远记失败、永远恢复不了，而线程还占着。
        // 交给 H1 的注册表对账 + 闸门恢复去重新拉起更干净。
        JdbcTemplate probeJdbc;
        RouteConfig cfg;
        try {
            cfg = registry.get(routeId, route.getActiveVersion());
            if (cfg == null) {
                stateRepository.markPaused(routeId, "配置尚未装配，等待下次对账重新拉起",
                        route.getActiveVersion());
                log.warn("路由 {} 的配置版本 {} 尚未装配，暂不提交消费者", routeId, route.getActiveVersion());
                return;
            }
            probeJdbc = dataSources.jdbc(cfg.target().datasourceId());
        } catch (RuntimeException e) {
            // 原因要可读：页面上的「暂停原因」直接取自这里
            stateRepository.markPaused(routeId, "目标数据源不可用，等待恢复后自动拉起",
                    route.getActiveVersion());
            log.error("目标数据源不可用，暂不提交消费者: route={} 原因={}", routeId, e.getMessage(), e);
            return;
        }

        RouteConsumer routeConsumer = new RouteConsumer(
                routeId, consumer, registry, batchManager, batchProps,
                stateRepository, healthGate, probeJdbc, probeInterval, metrics);

        consumers.put(routeId, routeConsumer);
        stateRepository.markRunning(routeId, route.getActiveVersion());
        executor.submit(routeConsumer);
        log.info("路由 {} 的 Consumer 已提交: topic={} tag={}", routeId, route.getTopic(), route.getTag());
    }

    /** 配置激活后调用，确保消费者存在。 */
    public synchronized void ensureStarted(long routeId) {
        CfgRoute route = routeMapper.selectById(routeId);
        if (route != null && "ACTIVE".equals(route.getStatus()) && route.getActiveVersion() != null) {
            startRoute(route);
        }
    }

    /**
     * 停止并移除某路由的消费者。
     *
     * <p>{@link RouteConsumer#close()} 只是置一个 volatile 标志，**不会 join 线程**：
     * 线程要等当前这一轮拉取/写入返回后才退出。因此本方法返回后，
     * 那个线程可能还会短暂跑一会儿。这是刻意的 —— 强行中断可能打断
     * 「已提交 PG 但尚未 ACK」的窗口，把一次正常处理变成重复投递。
     *
     * @return 是否确实停掉了一个消费者
     */
    public synchronized boolean stop(long routeId, String reason) {
        RouteConsumer existing = consumers.remove(routeId);
        if (existing == null) {
            return false;
        }
        existing.close();

        // 只有路由还在时才写消费状态。删除路由的场景下监听器也会走到这里，
        // 而此时 cfg_route 那一行已经没了：状态行既会被级联删除，写它还会撞外键
        // （insert 一条指向已删路由的 rt_consumer_state）。
        CfgRoute route = routeMapper.selectById(routeId);
        if (route != null) {
            stateRepository.markPaused(routeId, reason,
                    route.getActiveVersion() == null ? -1 : route.getActiveVersion());
        } else {
            log.debug("路由 {} 已不存在，跳过消费状态写入", routeId);
        }
        log.info("路由 {} 的 Consumer 已停止: {}", routeId, reason);
        return true;
    }

    /**
     * 重建某路由的消费者（Topic/Tag/目标表变更时使用）。
     *
     * <p>必须先停再起：{@code startRoute} 对已存在的路由会直接返回，
     * 不停掉的话改动不会生效。
     */
    public synchronized void restart(long routeId) {
        CfgRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            stop(routeId, "路由已删除");
            return;
        }
        if (!"ACTIVE".equals(route.getStatus()) || route.getActiveVersion() == null) {
            stop(routeId, "路由已停用");
            return;
        }
        boolean had = stop(routeId, "配置已变更，重建消费者");
        startRoute(route);
        log.info("路由 {} 的 Consumer 已重建（此前{}在运行）", routeId, had ? "" : "未");
    }

    public RouteConsumer consumerOf(long routeId) {
        return consumers.get(routeId);
    }

    /**
     * 仅供测试：临时切换 auto-start 开关。
     *
     * <p>集成测试基类为了不干扰断言把 auto-start 设为 false，但要么验证
     * 「补差任务确实会拉起 PAUSED 路由」，要么就得在不关它的前提下起整个上下文 ——
     * 那样后台消费者会跟断言抢消息，测试变得时好时坏。
     * 与其为这一个场景复制一整套配置，不如开一个显式的测试入口。
     */
    void setAutoStartForTest(boolean value) {
        this.autoStart = value;
    }

    public Map<Long, RouteConsumer> consumers() {
        return Map.copyOf(consumers);
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("正在停止全部 Consumer（{} 个）", consumers.size());
        consumers.values().forEach(RouteConsumer::close);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        consumers.clear();
    }
}
