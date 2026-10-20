package com.mqspg.config.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.persistence.JsonNodes;
import com.mqspg.config.dto.RouteDetailDto;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.TargetDto;
import com.mqspg.config.dto.VersionSummaryDto;
import com.mqspg.config.entity.CfgDatasource;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.entity.CfgTarget;
import com.mqspg.config.entity.CfgVersion;
import com.mqspg.config.entity.RtConsumerState;
import com.mqspg.config.event.ConfigChangedEvent;
import com.mqspg.config.mapper.CfgDatasourceMapper;
import com.mqspg.config.mapper.CfgRouteMapper;
import com.mqspg.config.mapper.CfgTargetMapper;
import com.mqspg.config.mapper.CfgVersionMapper;
import com.mqspg.config.mapper.RtConsumerStateMapper;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.config.registry.RouteConfigAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置域服务：查询、版本发布与激活（tech-design §12.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    /**
     * 允许被激活的版本状态。
     *
     * <p>必须包含 {@code INACTIVE}：一个曾被激活、随后被新版本取代的版本会被降级为
     * INACTIVE，而**回滚要激活的恰恰就是它**。若把它排除在外，回滚在
     * 「v1 生效 → v2 生效 → 想退回 v1」这个唯一有意义的场景下必然失败。
     *
     * <p>{@code DRAFT} 不在其中：未发布的草稿还没通过校验，不允许直接生效。
     */
    private static final List<String> ACTIVATABLE =
            List.of("VALID", "PUBLISHED", "ACTIVE", "INACTIVE");

    private final CfgRouteMapper routeMapper;
    private final CfgTargetMapper targetMapper;
    private final CfgVersionMapper versionMapper;
    private final CfgDatasourceMapper datasourceMapper;
    private final RtConsumerStateMapper consumerStateMapper;
    private final ConfigRegistry registry;
    private final RouteConfigAssembler assembler;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<RouteSummaryDto> listRoutes() {
        List<CfgRoute> routes = routeMapper.selectList(
                Wrappers.<CfgRoute>lambdaQuery().orderByAsc(CfgRoute::getId));
        // 批量装配，避免逐条查目标表 / 消费状态（C1：把 O(n) 次查询降为常数次）
        Map<Long, CfgTarget> targets = loadTargets(routes);
        Map<Long, RtConsumerState> states = loadConsumerStates(routes);
        List<RouteSummaryDto> out = new ArrayList<>(routes.size());
        for (CfgRoute r : routes) {
            out.add(RouteSummaries.build(r, targets.get(r.getTargetId()), states.get(r.getId())));
        }
        return out;
    }

    public RouteDetailDto getRoute(long routeId) {
        CfgRoute route = requireRoute(routeId);
        CfgTarget target = requireTarget(route.getTargetId());
        CfgDatasource ds = datasourceMapper.selectById(target.getDatasourceId());
        RtConsumerState state = consumerStateMapper.selectById(routeId);

        JsonNodeHolder holder = loadActiveContent(route);
        List<VersionSummaryDto> versions = listVersions(routeId);

        return new RouteDetailDto(
                RouteSummaries.build(route, target, state),
                new TargetDto(target.getId(), target.getName(), target.getDatasourceId(),
                        ds == null ? null : ds.getName(),
                        target.getSchemaName(), target.getTableName(),
                        readKeys(target), target.getUpdateTimeField()),
                holder.content(),
                holder.jslt(),
                holder.mappingStrategy(),
                holder.mappingCount(),
                versions);
    }

    public List<VersionSummaryDto> listVersions(long routeId) {
        CfgRoute route = requireRoute(routeId);
        List<CfgVersion> versions = versionMapper.selectList(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId));
        versions.sort(Comparator.comparing(CfgVersion::getVersion).reversed());
        List<VersionSummaryDto> out = new ArrayList<>(versions.size());
        for (CfgVersion v : versions) {
            out.add(new VersionSummaryDto(
                    v.getVersion(), v.getStatus(), v.getChangeNote(), v.getCreatedBy(),
                    v.getCreatedAt(), v.getPublishedAt(),
                    route.getActiveVersion() != null && route.getActiveVersion().equals(v.getVersion())));
        }
        return out;
    }

    /** 取某个版本的原始快照（供 Console 编辑/预览）。 */
    public CfgVersion getVersion(long routeId, int version) {
        CfgVersion v = versionMapper.selectOne(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getVersion, version));
        if (v == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND,
                    "配置版本不存在: routeId=%d version=%d".formatted(routeId, version));
        }
        return v;
    }

    /** 取已装配好的运行时配置（供 Dry Run / 预览复用）。 */
    public RouteConfig resolve(long routeId, int version) {
        CfgRoute route = requireRoute(routeId);
        CfgTarget target = requireTarget(route.getTargetId());
        return assembler.assemble(getVersion(routeId, version), target);
    }

    // ------------------------------------------------------------------
    // 激活
    // ------------------------------------------------------------------

    /**
     * 将指定版本置为 ACTIVE，并刷新注册表。
     *
     * <p>原 ACTIVE 版本降级为 INACTIVE。整个过程在一个事务内完成，
     * 事务提交后才刷新内存注册表 —— 避免消息读到尚未提交的配置。
     *
     * <p><b>并发保护</b>：开头先对 {@code cfg_route} 那一行加行锁
     * （{@code SELECT ... FOR UPDATE}）。没有这把锁时，两个并发请求会各自读到
     * 相同的 {@code previous}，各自把自己那一版置为 ACTIVE、都去降级同一个旧版本，
     * 结果留下**两个 ACTIVE 版本**；而 {@code cfg_route.active_version} 只记住后写者，
     * 另一个成为 `deleteVersion` 的保护看不见的幽灵。
     * 锁粒度是单条路由：不同路由的激活互不阻塞。
     */
    @Transactional
    public void activate(long routeId, int version) {
        // 先锁路由行，再读版本 —— 顺序不能反，否则锁之前的读已经拿到了旧状态
        CfgRoute route = requireRouteForUpdate(routeId);
        CfgVersion target = getVersion(routeId, version);
        String targetStatus = target.getStatus();

        if (!ACTIVATABLE.contains(targetStatus)) {
            throw new ProcessingException(ErrorCode.INVALID_INPUT,
                    "版本状态不允许激活: %s（仅允许 %s）".formatted(targetStatus, ACTIVATABLE));
        }

        Integer previous = route.getActiveVersion();
        OffsetDateTime now = OffsetDateTime.now();

        if (previous != null && previous != version) {
            // 加 status='ACTIVE' 条件：兜住历史脏数据（此前可能已经存在多个 ACTIVE），
            // 只降级真正处在 ACTIVE 的那一行，不误伤已发布但从未激活的版本
            versionMapper.update(null, Wrappers.<CfgVersion>lambdaUpdate()
                    .eq(CfgVersion::getRouteId, routeId)
                    .eq(CfgVersion::getVersion, previous)
                    .eq(CfgVersion::getStatus, "ACTIVE")
                    .set(CfgVersion::getStatus, "INACTIVE"));
        }

        // 条件更新 + 影响行数校验：目标版本在加锁期间仍可能被别的路径改动
        // （例如校验失败回填状态），此时宁可让调用方刷新重试，也不要静默改掉
        int updated = versionMapper.update(null, Wrappers.<CfgVersion>lambdaUpdate()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getVersion, version)
                .eq(CfgVersion::getStatus, targetStatus)
                .set(CfgVersion::getStatus, "ACTIVE")
                .set(CfgVersion::getPublishedAt, now));
        if (updated != 1) {
            throw new ProcessingException(ErrorCode.CONFLICT,
                    "版本状态已被并发修改，请刷新后重试");
        }

        routeMapper.update(null, Wrappers.<CfgRoute>lambdaUpdate()
                .eq(CfgRoute::getId, routeId)
                .set(CfgRoute::getActiveVersion, version)
                .set(CfgRoute::getStatus, "ACTIVE")
                .set(CfgRoute::getUpdatedAt, now));

        ensureConsumerState(routeId, version);

        // 注册表刷新必须发生在事务提交之后：否则消费线程可能读到尚未提交
        // （甚至最终回滚）的配置。由 AFTER_COMMIT 监听器完成，见 ConfigRefreshListener。
        events.publishEvent(ConfigChangedEvent.versionActivated(routeId));

        log.info("路由 {} 已激活配置版本 {}（原版本 {}）", routeId, version, previous);
    }

    /**
     * 确保运行状态行存在，并把 {@code bind_version} 更新为最新激活的版本。
     *
     * <p><b>为什么是 upsert 而不是读后写</b>：`selectById` 判空再 `insert` 会撞并发 ——
     * 同一路由的两次激活都可能看到「不存在」然后各自插入，后者主键冲突，
     * 异常一路变成一句数据库原文。
     *
     * <p><b>为什么顺带更新 bind_version</b>：上一版只在首次插入时写它，
     * 之后再激活新版本永远不动，字段名与内容长期不一致。
     * 这里把它定为「最近一次状态变更时所处的 active 版本」——
     * 注意**不是**「消息绑定版本」，那是重试任务上的 {@code config_version}。
     */
    public void ensureConsumerState(long routeId, int bindVersion) {
        consumerStateMapper.upsertBindVersion(routeId, bindVersion);
    }

    // ------------------------------------------------------------------

    private CfgRoute requireRoute(long routeId) {
        CfgRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND, "路由不存在: " + routeId);
        }
        return route;
    }

    /**
     * 读取路由并加行锁，供「读当前状态 → 据此写多张表」的写路径使用。
     */
    public CfgRoute requireRouteForUpdate(long routeId) {
        CfgRoute route = routeMapper.selectByIdForUpdate(routeId);
        if (route == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND, "路由不存在: " + routeId);
        }
        return route;
    }

    private CfgTarget requireTarget(Long targetId) {
        CfgTarget target = targetMapper.selectById(targetId);
        if (target == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND, "目标表定义不存在: " + targetId);
        }
        return target;
    }

    private Map<Long, CfgTarget> loadTargets(List<CfgRoute> routes) {
        Set<Long> ids = new java.util.HashSet<>();
        for (CfgRoute r : routes) {
            if (r.getTargetId() != null) {
                ids.add(r.getTargetId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, CfgTarget> out = new java.util.HashMap<>(ids.size());
        for (CfgTarget t : targetMapper.selectBatchIds(ids)) {
            out.put(t.getId(), t);
        }
        return out;
    }

    private Map<Long, RtConsumerState> loadConsumerStates(List<CfgRoute> routes) {
        Set<Long> ids = new java.util.HashSet<>();
        for (CfgRoute r : routes) {
            if (r.getId() != null) {
                ids.add(r.getId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, RtConsumerState> out = new java.util.HashMap<>(ids.size());
        for (RtConsumerState s : consumerStateMapper.selectBatchIds(ids)) {
            out.put(s.getRouteId(), s);
        }
        return out;
    }

    private JsonNodeHolder loadActiveContent(CfgRoute route) {
        if (route.getActiveVersion() == null) {
            return JsonNodeHolder.empty();
        }
        CfgVersion v = versionMapper.selectOne(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, route.getId())
                .eq(CfgVersion::getVersion, route.getActiveVersion()));
        if (v == null || v.getContent() == null) {
            return JsonNodeHolder.empty();
        }
        String strategy = JsonNodes.text(v.getContent(), "mappingStrategy", "EXACT");
        String jslt = JsonNodes.text(v.getContent(), "jslt");
        int count = v.getContent().path("mappings").isArray() ? v.getContent().path("mappings").size() : 0;
        // 转成纯 Java 结构再出 DTO：Web 层是 Jackson 3，不认识 Jackson 2 的 JsonNode
        return new JsonNodeHolder(JsonNodes.toPlain(v.getContent()), jslt, strategy, count);
    }

    private List<String> readKeys(CfgTarget target) {
        if (target.getUpsertKeys() == null || !target.getUpsertKeys().isArray()) {
            return List.of("id");
        }
        List<String> out = new ArrayList<>();
        target.getUpsertKeys().forEach(n -> out.add(n.asText()));
        return out;
    }

    private record JsonNodeHolder(Object content, String jslt, String mappingStrategy, int mappingCount) {
        static JsonNodeHolder empty() {
            return new JsonNodeHolder(null, null, null, 0);
        }
    }
}
