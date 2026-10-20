package com.mqspg.config.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.entity.CfgTarget;
import com.mqspg.config.entity.CfgVersion;
import com.mqspg.config.mapper.CfgRouteMapper;
import com.mqspg.config.mapper.CfgTargetMapper;
import com.mqspg.config.mapper.CfgVersionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于数据库的配置注册表实现。
 *
 * <p>两级缓存：
 * <ul>
 *   <li>{@code active} —— routeId → 当前 ACTIVE 快照，接收消息时读，发布后原子替换；</li>
 *   <li>{@code snapshots} —— (routeId, version) → 快照，按版本号取，保证历史版本不被替换。</li>
 * </ul>
 *
 * <p><b>关于版本回收</b>：V1 不做过期淘汰。{@code snapshots} 只在进程存活期内增长，
 * 而配置版本数量级为「每路由数十个」，内存占用可忽略。这一点是刻意的 ——
 * 淘汰一个正被批次引用的版本会导致「运行中的消息被动态换配置」，违反 PRD §19。
 * 若后续版本数增长到需要淘汰，必须引入引用计数而非简单 LRU。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbConfigRegistry implements ConfigRegistry {

    private final CfgRouteMapper routeMapper;
    private final CfgTargetMapper targetMapper;
    private final CfgVersionMapper versionMapper;
    private final RouteConfigAssembler assembler;

    private final Map<Long, AtomicReference<RouteConfig>> active = new ConcurrentHashMap<>();
    private final Map<VersionKey, RouteConfig> snapshots = new ConcurrentHashMap<>();

    /** 关掉周期性对账（单测与「配置由外部编排保证」的部署会需要）。 */
    @Value("${mqs-pg.registry.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    private record VersionKey(long routeId, int version) {
    }

    @PostConstruct
    public void init() {
        refreshAll();
    }

    /**
     * 周期性对账：把内存注册表拉回与数据库一致。
     *
     * <p><b>为什么需要它</b>：{@link ConfigRefreshListener} 的 catch 里写着
     * 「下一次 refreshAll 会纠正」，但运行期**没有任何周期性刷新** ——
     * {@code refreshAll} 只在 {@code @PostConstruct} 和 {@code ApplicationReadyEvent} 调过。
     * 于是一次 DB 抖动、或目标表被删导致 AFTER_COMMIT 处理失败之后，
     * 内存注册表与本进程消费者会一直停在旧状态，直到重启。
     * 注释承诺的兜底并不存在，这属于典型的「注释撒谎」。
     *
     * <p><b>为什么是轻量对账而不是 refreshAll</b>：`refreshAll` 对每条 ACTIVE 路由都要
     * 再读 version 与 target。这里只跑一条
     * {@code SELECT id, active_version FROM cfg_route WHERE status='ACTIVE'}，
     * 逐条比较内存里的版本号，**只在不一致时才** {@code refresh}。
     * 稳定运行时这条查询每秒一次都无所谓。
     *
     * <p>间隔可配置（`mqs-pg.registry.refresh-interval`，默认 60s）。
     * 关闭请用 `mqs-pg.registry.reconcile-enabled=false` 而不是把间隔设为 0 ——
     * Spring 的 `fixedDelay` 要求正数，设 0 会在启动阶段就抛异常，
     * 而不是「安静地不跑」。
     *
     * <p>注意开关只作用于**调度入口**（{@link #scheduledReconcile()}）：
     * {@code reconcile()} 本身永远可手动调用，否则测试里想主动触发对账
     * 也会被这个开关挡掉，测出来的是「开关生效」而不是「对账有效」。
     */
    @Scheduled(fixedDelayString = "${mqs-pg.registry.refresh-interval:60s}")
    public void scheduledReconcile() {
        if (!reconcileEnabled) {
            return;
        }
        reconcile();
    }

    /** 对账本体。手动调用不受 {@code reconcile-enabled} 影响。 */
    public void reconcile() {
        List<CfgRoute> rows;
        try {
            rows = routeMapper.selectList(Wrappers.<CfgRoute>lambdaQuery()
                    .select(CfgRoute::getId, CfgRoute::getActiveVersion)
                    .eq(CfgRoute::getStatus, "ACTIVE"));
        } catch (RuntimeException e) {
            // 库暂时不可用时不要刷屏，也不要清空注册表 —— 保留旧快照比清空更安全
            log.warn("注册表对账跳过（查询失败）: {}", e.getMessage());
            return;
        }

        Set<Long> liveActive = new HashSet<>();
        int fixed = 0;
        for (CfgRoute row : rows) {
            long id = row.getId();
            Integer expected = row.getActiveVersion();
            if (expected == null) {
                continue;
            }
            liveActive.add(id);
            int actual = activeVersion(id);
            if (actual != expected) {
                log.info("注册表对账: 路由 {} 内存版本 {} ≠ 库内 {}，正在刷新",
                        id, actual, expected);
                try {
                    refresh(id);
                    fixed++;
                } catch (RuntimeException e) {
                    log.warn("注册表对账刷新失败，留待下一轮: route={} 原因={}", id, e.getMessage());
                }
            }
        }

        // 库里已不再 ACTIVE、内存里还挂着的，一并摘掉（事件丢失时同样会走到这里）
        for (Long id : List.copyOf(active.keySet())) {
            if (!liveActive.contains(id)) {
                active.remove(id);
                log.info("注册表对账: 路由 {} 已不再生效，已从注册表移除", id);
                fixed++;
            }
        }

        if (fixed > 0) {
            log.info("注册表对账: 修正 {} 条", fixed);
        }
    }

    @Override
    public int activeVersion(long routeId) {
        RouteConfig c = activeOf(routeId);
        return c == null ? -1 : c.version();
    }

    @Override
    public RouteConfig active(long routeId) {
        return activeOf(routeId);
    }

    @Override
    public RouteConfig get(long routeId, int version) {
        VersionKey key = new VersionKey(routeId, version);
        RouteConfig cached = snapshots.get(key);
        if (cached != null) {
            return cached;
        }
        // 不在 computeIfAbsent 里做 I/O：映射函数在桶锁内执行，会阻塞其他 key
        RouteConfig loaded = loadSnapshot(routeId, version);
        if (loaded != null) {
            snapshots.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public List<Long> activeRouteIds() {
        return active.values().stream()
                .map(AtomicReference::get)
                .filter(java.util.Objects::nonNull)
                .map(RouteConfig::routeId)
                .toList();
    }

    @Override
    public void refresh(long routeId) {
        RouteConfig cfg = loadActive(routeId);
        if (cfg == null) {
            active.remove(routeId);
            log.info("路由 {} 无 ACTIVE 配置版本，已从注册表移除", routeId);
            return;
        }
        snapshots.put(new VersionKey(routeId, cfg.version()), cfg);
        active.computeIfAbsent(routeId, k -> new AtomicReference<>()).set(cfg);
        log.info("路由 {} 配置已刷新: version={} target={} mappings={}",
                routeId, cfg.version(), cfg.target().qualifiedTable(), cfg.mappings().size());
    }

    @Override
    public void evict(long routeId, int version) {
        VersionKey key = new VersionKey(routeId, version);
        RouteConfig removed = snapshots.remove(key);
        if (removed != null) {
            log.info("路由 {} 的版本 {} 已删除，内存快照已驱逐", routeId, version);
        }
    }

    @Override
    public void refreshAll() {
        List<CfgRoute> routes = routeMapper.selectList(
                Wrappers.<CfgRoute>lambdaQuery().eq(CfgRoute::getStatus, "ACTIVE"));
        for (CfgRoute r : routes) {
            refresh(r.getId());
        }
        log.info("配置注册表初始化完成，共加载 {} 个 ACTIVE 路由", routes.size());
    }

    // ------------------------------------------------------------------

    private RouteConfig activeOf(long routeId) {
        AtomicReference<RouteConfig> ref = active.get(routeId);
        return ref == null ? null : ref.get();
    }

    /**
     * 载入某个路由的当前 ACTIVE 快照。
     *
     * <p>**必须校验 {@code route.status}**：`refreshAll` 只捞 ACTIVE 路由，
     * 但 `refresh(routeId)` 是事件驱动的（路由被改、版本被激活），
     * 此时路由可能刚被停用。不判状态的话，注册表会继续持有它的 ACTIVE 快照，
     * `activeVersion()` 也照样返回值，而消费者已被停掉 ——
     * 「注册表认为在跑、实际没在跑」，排查时最费时的那类不一致。
     */
    private RouteConfig loadActive(long routeId) {
        CfgRoute route = routeMapper.selectById(routeId);
        if (route == null || route.getActiveVersion() == null) {
            return null;
        }
        if (!"ACTIVE".equals(route.getStatus())) {
            log.info("路由 {} 状态为 {}，不视为生效路由", routeId, route.getStatus());
            return null;
        }
        return loadSnapshot(routeId, route.getActiveVersion());
    }

    private RouteConfig loadSnapshot(long routeId, int version) {
        CfgVersion v = versionMapper.selectOne(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getVersion, version));
        if (v == null) {
            return null;
        }
        CfgRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND, "路由不存在: " + routeId);
        }
        CfgTarget target = targetMapper.selectById(route.getTargetId());
        if (target == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND,
                    "路由 %d 的目标表定义不存在: targetId=%d".formatted(routeId, route.getTargetId()));
        }
        return assembler.assemble(v, target);
    }
}
