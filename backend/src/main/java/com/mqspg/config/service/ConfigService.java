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
        List<RouteSummaryDto> out = new ArrayList<>(routes.size());
        for (CfgRoute r : routes) {
            out.add(toSummary(r));
        }
        return out;
    }

    public RouteDetailDto getRoute(long routeId) {
        CfgRoute route = requireRoute(routeId);
        CfgTarget target = requireTarget(route.getTargetId());
        CfgDatasource ds = datasourceMapper.selectById(target.getDatasourceId());

        JsonNodeHolder holder = loadActiveContent(route);
        List<VersionSummaryDto> versions = listVersions(routeId);

        return new RouteDetailDto(
                toSummary(route),
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
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
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
     */
    @Transactional
    public void activate(long routeId, int version) {
        CfgRoute route = requireRoute(routeId);
        CfgVersion target = getVersion(routeId, version);

        if (!ACTIVATABLE.contains(target.getStatus())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "版本状态不允许激活: %s（仅允许 %s）".formatted(target.getStatus(), ACTIVATABLE));
        }

        Integer previous = route.getActiveVersion();
        OffsetDateTime now = OffsetDateTime.now();

        if (previous != null && previous != version) {
            versionMapper.update(null, Wrappers.<CfgVersion>lambdaUpdate()
                    .eq(CfgVersion::getRouteId, routeId)
                    .eq(CfgVersion::getVersion, previous)
                    .set(CfgVersion::getStatus, "INACTIVE"));
        }

        versionMapper.update(null, Wrappers.<CfgVersion>lambdaUpdate()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getVersion, version)
                .set(CfgVersion::getStatus, "ACTIVE")
                .set(CfgVersion::getPublishedAt, now));

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

    /** 确保存在运行状态行。 */
    public void ensureConsumerState(long routeId, int bindVersion) {
        RtConsumerState existing = consumerStateMapper.selectById(routeId);
        if (existing == null) {
            RtConsumerState s = new RtConsumerState();
            s.setRouteId(routeId);
            s.setStatus("PAUSED");
            s.setReason("等待 Consumer 启动");
            s.setBindVersion(bindVersion);
            s.setUpdatedAt(OffsetDateTime.now());
            consumerStateMapper.insert(s);
        }
    }

    // ------------------------------------------------------------------

    private CfgRoute requireRoute(long routeId) {
        CfgRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "路由不存在: " + routeId);
        }
        return route;
    }

    private CfgTarget requireTarget(Long targetId) {
        CfgTarget target = targetMapper.selectById(targetId);
        if (target == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "目标表定义不存在: " + targetId);
        }
        return target;
    }

    private RouteSummaryDto toSummary(CfgRoute route) {
        CfgTarget target = targetMapper.selectById(route.getTargetId());
        RtConsumerState state = consumerStateMapper.selectById(route.getId());
        return new RouteSummaryDto(
                route.getId(), route.getName(), route.getTopic(), route.getTag(),
                route.getTargetId(),
                target == null ? null : target.getSchemaName() + "." + target.getTableName(),
                route.getActiveVersion(), route.getStatus(),
                state == null ? null : state.getStatus(),
                state == null ? null : state.getReason());
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
