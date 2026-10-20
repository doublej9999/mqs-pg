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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    private record VersionKey(long routeId, int version) {
    }

    @PostConstruct
    public void init() {
        refreshAll();
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
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "路由不存在: " + routeId);
        }
        CfgTarget target = targetMapper.selectById(route.getTargetId());
        if (target == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "路由 %d 的目标表定义不存在: targetId=%d".formatted(routeId, route.getTargetId()));
        }
        return assembler.assemble(v, target);
    }
}
