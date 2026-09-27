package com.mqspg.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.mapper.CfgRouteMapper;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsConsumerFactory;
import com.mqspg.writer.batch.BatchManager;
import com.mqspg.writer.batch.BatchProperties;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        // 探测用的连接：取该路由目标库
        JdbcTemplate probeJdbc = null;
        var cfg = registry.get(routeId, route.getActiveVersion());
        if (cfg != null) {
            try {
                probeJdbc = dataSources.jdbc(cfg.target().datasourceId());
            } catch (RuntimeException e) {
                log.error("目标数据源不可用，Consumer 将以暂停状态启动: route={}", routeId, e);
            }
        }
        if (probeJdbc == null) {
            // 数据源不可用时用一个必然失败的探测，让健康闸门负责暂停/恢复
            probeJdbc = new JdbcTemplate();
        }

        RouteConsumer routeConsumer = new RouteConsumer(
                routeId, consumer, registry, batchManager, batchProps,
                stateRepository, healthGate, probeJdbc, probeInterval);

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

    public RouteConsumer consumerOf(long routeId) {
        return consumers.get(routeId);
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
