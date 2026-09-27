package com.mqspg.config.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.crypto.PasswordCodec;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.TargetRef;
import com.mqspg.common.persistence.JsonNodes;
import com.mqspg.config.dto.DatasourceDto;
import com.mqspg.config.dto.DatasourceRequest;
import com.mqspg.config.dto.DraftDto;
import com.mqspg.config.dto.DraftRequest;
import com.mqspg.config.dto.RouteRequest;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.TargetDto;
import com.mqspg.config.dto.TargetRequest;
import com.mqspg.config.dto.ValidationResultDto;
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
import com.mqspg.config.registry.RouteConfigAssembler;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import com.mqspg.writer.metadata.ColumnMeta;
import com.mqspg.writer.metadata.TargetMetadataReader;
import com.mqspg.writer.metadata.TargetTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置域**写**操作：数据源、目标表、路由、版本草稿的生命周期。
 *
 * <p>与只读的 {@link ConfigService} 分开，是因为两者的失败语义完全不同：
 * 读接口出错只需报错，写接口出错还可能留下半成品配置和已经启动的消费者。
 * 把写操作集中在一个类里，才能保证「改库 + 发事件 + 失效缓存」这三件事
 * 在每一处都不被漏掉。
 *
 * <p><b>事务与事件</b>：所有写方法都在事务内，并且只发布
 * {@link ConfigChangedEvent}，由 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 去刷新注册表与启停消费者。绝不在事务里直接动消费者 —— 事务回滚时
 * 消费者已经按一份不存在的配置跑起来了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigAdminService {

    /** 未完成状态：占用中的重试任务会随路由删除而变成孤儿。 */
    private static final List<String> UNFINISHED_RETRY_STATUSES = List.of("PENDING", "RUNNING");

    private final CfgDatasourceMapper datasourceMapper;
    private final CfgTargetMapper targetMapper;
    private final CfgRouteMapper routeMapper;
    private final CfgVersionMapper versionMapper;
    private final RtConsumerStateMapper consumerStateMapper;
    private final RtRetryTaskMapper retryTaskMapper;
    private final PasswordCodec passwordCodec;
    private final TargetDataSourceRegistry dataSources;
    private final TargetMetadataReader metadataReader;
    private final RouteConfigAssembler assembler;
    private final ConfigValidator validator;
    private final ConfigService configService;
    private final ApplicationEventPublisher events;

    // ==================================================================
    // 数据源
    // ==================================================================

    public List<DatasourceDto> listDatasources() {
        List<CfgDatasource> all = datasourceMapper.selectList(
                Wrappers.<CfgDatasource>lambdaQuery().orderByAsc(CfgDatasource::getId));
        List<DatasourceDto> out = new ArrayList<>(all.size());
        for (CfgDatasource ds : all) {
            out.add(toDatasourceDto(ds));
        }
        return out;
    }

    public DatasourceDto getDatasource(long id) {
        return toDatasourceDto(requireDatasource(id));
    }

    @Transactional
    public DatasourceDto createDatasource(DatasourceRequest req) {
        requireDatasourceFields(req, true);
        if (nameTaken(req.name(), null)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "数据源名称已存在: " + req.name());
        }
        OffsetDateTime now = OffsetDateTime.now();
        CfgDatasource ds = new CfgDatasource();
        ds.setName(req.name().trim());
        ds.setJdbcUrl(req.jdbcUrl().trim());
        ds.setUsername(req.username());
        ds.setPasswordEnc(passwordCodec.encode(req.password()));
        ds.setPoolConfig(JsonNodes.toJsonNode(req.poolConfig()));
        ds.setCreatedAt(now);
        ds.setUpdatedAt(now);
        datasourceMapper.insert(ds);
        log.info("数据源已创建: id={} name={} url={}", ds.getId(), ds.getName(), ds.getJdbcUrl());
        return toDatasourceDto(ds);
    }

    @Transactional
    public DatasourceDto updateDatasource(long id, DatasourceRequest req) {
        CfgDatasource existing = requireDatasource(id);
        requireDatasourceFields(req, false);
        if (nameTaken(req.name(), id)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "数据源名称已存在: " + req.name());
        }
        // 同上：poolConfig 是 JsonNode，必须走实体上的 JsonbTypeHandler
        CfgDatasource patch = new CfgDatasource();
        patch.setId(id);
        patch.setName(req.name().trim());
        patch.setJdbcUrl(req.jdbcUrl().trim());
        patch.setUsername(req.username());
        patch.setUpdatedAt(OffsetDateTime.now());
        // 密码留空 = 保留原值。页面不回显密码，所以「没改密码」必须能表达出来。
        // updateById 跳过 null，这里不设字段就等于「不改」
        if (req.password() != null && !req.password().isBlank()) {
            patch.setPasswordEnc(passwordCodec.encode(req.password()));
        }
        if (req.poolConfig() != null) {
            patch.setPoolConfig(JsonNodes.toJsonNode(req.poolConfig()));
        }
        datasourceMapper.updateById(patch);

        // 连接参数可能已变，丢弃旧池，下次使用按新参数重建
        dataSources.invalidate(id);
        log.info("数据源已更新: id={} name={}（旧名 {}）", id, req.name(), existing.getName());
        return toDatasourceDto(requireDatasource(id));
    }

    @Transactional
    public void deleteDatasource(long id) {
        CfgDatasource ds = requireDatasource(id);
        List<CfgTarget> refs = targetMapper.selectList(
                Wrappers.<CfgTarget>lambdaQuery().eq(CfgTarget::getDatasourceId, id));
        if (!refs.isEmpty()) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "数据源「%s」被 %d 个目标表引用，无法删除: %s".formatted(
                            ds.getName(), refs.size(), targetNames(refs)));
        }
        datasourceMapper.deleteById(id);
        dataSources.invalidate(id);
        log.info("数据源已删除: id={} name={}", id, ds.getName());
    }

    // ==================================================================
    // 目标表
    // ==================================================================

    public List<TargetDto> listTargets() {
        List<CfgTarget> all = targetMapper.selectList(
                Wrappers.<CfgTarget>lambdaQuery().orderByAsc(CfgTarget::getId));
        List<TargetDto> out = new ArrayList<>(all.size());
        for (CfgTarget t : all) {
            out.add(toTargetDto(t));
        }
        return out;
    }

    @Transactional
    public TargetDto createTarget(TargetRequest req) {
        requireTargetFields(req);
        CfgDatasource ds = requireDatasource(req.datasourceId());
        if (targetNameTaken(req.name(), null)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "目标表名称已存在: " + req.name());
        }
        if (sameTableTaken(req, null)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "同一数据源下已存在该表定义: %s.%s".formatted(req.schemaName(), req.tableName()));
        }
        OffsetDateTime now = OffsetDateTime.now();
        CfgTarget t = new CfgTarget();
        t.setName(req.name().trim());
        t.setDatasourceId(ds.getId());
        t.setSchemaName(normalizeSchema(req.schemaName()));
        t.setTableName(req.tableName().trim());
        t.setUpsertKeys(JsonNodes.toJsonNode(normalizeKeys(req.upsertKeys())));
        t.setUpdateTimeField(defaultIfBlank(req.updateTimeField(), "update_time"));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        targetMapper.insert(t);
        log.info("目标表已创建: id={} {} -> {}",
                t.getId(), t.getName(), t.getSchemaName() + "." + t.getTableName());
        return toTargetDto(t);
    }

    @Transactional
    public TargetDto updateTarget(long id, TargetRequest req) {
        CfgTarget existing = requireTarget(id);
        requireTargetFields(req);
        CfgDatasource ds = requireDatasource(req.datasourceId());
        if (targetNameTaken(req.name(), id)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "目标表名称已存在: " + req.name());
        }
        if (sameTableTaken(req, id)) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "同一数据源下已存在该表定义: %s.%s".formatted(req.schemaName(), req.tableName()));
        }
        // upsertKeys 是 JsonNode，必须走实体上的 JsonbTypeHandler（见 saveDraft 的说明）
        CfgTarget patch = new CfgTarget();
        patch.setId(id);
        patch.setName(req.name().trim());
        patch.setDatasourceId(ds.getId());
        patch.setSchemaName(normalizeSchema(req.schemaName()));
        patch.setTableName(req.tableName().trim());
        patch.setUpsertKeys(JsonNodes.toJsonNode(normalizeKeys(req.upsertKeys())));
        patch.setUpdateTimeField(defaultIfBlank(req.updateTimeField(), "update_time"));
        patch.setUpdatedAt(OffsetDateTime.now());
        targetMapper.updateById(patch);

        // 表结构元数据是按 TargetRef 缓存的，改完必须失效，否则仍按旧列写 SQL
        metadataReader.invalidate(assembler.toTargetRef(existing));
        log.info("目标表已更新: id={} name={}", id, req.name());
        return toTargetDto(requireTarget(id));
    }

    @Transactional
    public void deleteTarget(long id) {
        CfgTarget t = requireTarget(id);
        List<CfgRoute> refs = routeMapper.selectList(
                Wrappers.<CfgRoute>lambdaQuery().eq(CfgRoute::getTargetId, id));
        if (!refs.isEmpty()) {
            String names = refs.stream().map(CfgRoute::getName).collect(Collectors.joining("、"));
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "目标表「%s」被 %d 条路由引用，无法删除: %s".formatted(t.getName(), refs.size(), names));
        }
        targetMapper.deleteById(id);
        metadataReader.invalidate(assembler.toTargetRef(t));
        log.info("目标表已删除: id={} name={}", id, t.getName());
    }

    // ==================================================================
    // 路由
    // ==================================================================

    @Transactional
    public RouteSummaryDto createRoute(RouteRequest req) {
        requireRouteFields(req);
        CfgTarget target = requireTarget(req.targetId());
        checkRouteUnique(req.topic(), req.tag(), null);

        OffsetDateTime now = OffsetDateTime.now();
        CfgRoute route = new CfgRoute();
        route.setName(req.name().trim());
        route.setTopic(req.topic().trim());
        route.setTag(normalizeTag(req.tag()));
        route.setTargetId(target.getId());
        // 新建即 INACTIVE：还没有配置版本，此时启动消费者毫无意义
        route.setStatus("INACTIVE");
        route.setCreatedAt(now);
        route.setUpdatedAt(now);
        routeMapper.insert(route);

        events.publishEvent(ConfigChangedEvent.routeUpserted(route.getId()));
        log.info("路由已创建: id={} name={} topic={} tag={}",
                route.getId(), route.getName(), route.getTopic(), route.getTag());
        return toSummary(route);
    }

    @Transactional
    public RouteSummaryDto updateRoute(long routeId, RouteRequest req) {
        CfgRoute route = requireRoute(routeId);
        requireRouteFields(req);
        CfgTarget target = requireTarget(req.targetId());
        checkRouteUnique(req.topic(), req.tag(), routeId);

        routeMapper.update(null, Wrappers.<CfgRoute>lambdaUpdate()
                .eq(CfgRoute::getId, routeId)
                .set(CfgRoute::getName, req.name().trim())
                .set(CfgRoute::getTopic, req.topic().trim())
                .set(CfgRoute::getTag, normalizeTag(req.tag()))
                .set(CfgRoute::getTargetId, target.getId())
                .set(CfgRoute::getUpdatedAt, OffsetDateTime.now()));

        // Topic/Tag 可能变了 —— 监听器会重建消费者，否则它会继续拉旧 Topic
        events.publishEvent(ConfigChangedEvent.routeUpserted(routeId));
        log.info("路由已更新: id={} name={} topic={} tag={}",
                routeId, req.name(), req.topic(), req.tag());
        return toSummary(requireRoute(routeId));
    }

    /**
     * 删除路由。
     *
     * <p>{@code cfg_version} 与 {@code rt_consumer_state} 由 DDL 的
     * {@code ON DELETE CASCADE} 清理；{@code rt_retry_task} 与 {@code rt_error_record}
     * **刻意没有外键**，历史行会保留 —— 它们对排障有价值，且页面仍能按 routeId 查到。
     * 但也正因没有外键，未完成的重试任务会变成无人处理的孤儿，
     * 所以默认拒绝删除，要求显式 {@code force}。
     *
     * @param force true 表示已知会丢弃未完成的重试任务，仍然删除
     */
    @Transactional
    public void deleteRoute(long routeId, boolean force) {
        CfgRoute route = requireRoute(routeId);
        if (!force) {
            Long unfinished = retryTaskMapper.selectCount(Wrappers.<RtRetryTask>lambdaQuery()
                    .eq(RtRetryTask::getRouteId, routeId)
                    .in(RtRetryTask::getStatus, UNFINISHED_RETRY_STATUSES));
            if (unfinished != null && unfinished > 0) {
                throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                        "路由「%s」还有 %d 条未完成的重试任务，删除会使其成为孤儿；确认丢弃可加 force=true"
                                .formatted(route.getName(), unfinished));
            }
        }
        routeMapper.deleteById(routeId);
        // 提交后监听器会 refresh（把它从注册表摘掉）并停掉消费者
        events.publishEvent(ConfigChangedEvent.routeRemoved(routeId));
        log.info("路由已删除: id={} name={} force={}", routeId, route.getName(), force);
    }

    // ==================================================================
    // 草稿与版本
    // ==================================================================

    /**
     * 读取可编辑的草稿视图。
     *
     * <p>没有草稿时**不会**凭空建一行，而是基于 ACTIVE 版本合成一份未持久化的视图
     * （{@code persisted=false}）。这样「新建路由 → 打开配置页」不会留下
     * 一串用户没真正编辑过的空版本。
     */
    public DraftDto getDraft(long routeId) {
        CfgRoute route = requireRoute(routeId);
        CfgVersion draft = findDraft(routeId);
        if (draft != null) {
            return toDraft(routeId, draft, true);
        }
        if (route.getActiveVersion() != null) {
            CfgVersion active = findVersion(routeId, route.getActiveVersion());
            if (active != null) {
                return new DraftDto(routeId, null, "DRAFT",
                        JsonNodes.toPlain(active.getContent()),
                        JsonNodes.text(active.getContent(), "jslt"),
                        JsonNodes.text(active.getContent(), "mappingStrategy", "EXACT"),
                        mappingCount(active.getContent()),
                        "从生效版本 %d 复制，尚未保存".formatted(route.getActiveVersion()),
                        null, false);
            }
        }
        return new DraftDto(routeId, null, "DRAFT", emptyContent(), null, "EXACT", 0,
                null, null, false);
    }

    @Transactional
    public DraftDto saveDraft(long routeId, DraftRequest req) {
        requireRoute(routeId);
        if (req == null || req.content() == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "草稿内容不能为空");
        }
        JsonNode content = JsonNodes.toJsonNode(req.content());
        if (content == null || !content.isObject()) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "草稿内容必须是 JSON 对象，形如 {mappingStrategy, jslt, mappings[]}");
        }

        CfgVersion draft = findDraft(routeId);
        if (draft == null) {
            return toDraft(routeId, insertVersion(routeId, nextVersionNumber(routeId), content,
                    req.changeNote()), true);
        }
        if (!isEditable(draft.getStatus())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "版本 %d 处于 %s，不可编辑".formatted(draft.getVersion(), draft.getStatus()));
        }
        // 用「实体 + updateById」而不是 LambdaUpdateWrapper.set(...)。
        // content 是 JsonNode，只有实体字段上声明的 JsonbTypeHandler 认它；
        // .set() 会把裸 JsonNode 直接交给 JDBC 驱动，PG 无法推断它的 SQL 类型而报错。
        // updateById 的 NOT_NULL 策略顺带满足「只改传入字段」的语义。
        CfgVersion patch = new CfgVersion();
        patch.setId(draft.getId());
        patch.setContent(content);
        patch.setChangeNote(req.changeNote());
        patch.setStatus("DRAFT");
        versionMapper.updateById(patch);
        draft.setContent(content);
        draft.setChangeNote(req.changeNote());
        draft.setStatus("DRAFT");
        return toDraft(routeId, draft, true);
    }

    /** 以当前 ACTIVE 版本为模板新建一个草稿版本。 */
    @Transactional
    public VersionSummaryDto createVersion(long routeId) {
        CfgRoute route = requireRoute(routeId);
        if (findDraft(routeId) != null) {
            // 保持「每个路由至多一个草稿」的不变量：否则 getDraft/saveDraft 会挑到哪一个不确定
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "该路由已有草稿版本，请先发布或删除它再新建");
        }
        JsonNode content = null;
        if (route.getActiveVersion() != null) {
            CfgVersion active = findVersion(routeId, route.getActiveVersion());
            if (active != null) {
                content = active.getContent();
            }
        }
        if (content == null) {
            content = JsonNodes.toJsonNode(emptyContent());
        }
        CfgVersion created = insertVersion(routeId, nextVersionNumber(routeId), content, "新建版本");
        return toVersionSummary(route, created);
    }

    public ValidationResultDto validateVersion(long routeId, int version) {
        CfgRoute route = requireRoute(routeId);
        CfgVersion v = requireVersion(routeId, version);
        return validator.validate(route, targetMapper.selectById(route.getTargetId()), v.getContent());
    }

    /**
     * 发布版本：校验通过才置 {@code PUBLISHED}。
     *
     * <p>校验不通过时**不抛异常**，而是把问题清单原样返回 —— 页面需要逐条展示
     * 「哪一列、什么问题」，包在一个异常消息里就没法结构化了。
     */
    @Transactional
    public ValidationResultDto publishVersion(long routeId, int version) {
        CfgRoute route = requireRoute(routeId);
        CfgVersion v = requireVersion(routeId, version);
        if (!isEditable(v.getStatus())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "版本 %d 处于 %s，不可发布".formatted(version, v.getStatus()));
        }
        ValidationResultDto result =
                validator.validate(route, targetMapper.selectById(route.getTargetId()), v.getContent());
        if (!result.valid()) {
            log.warn("版本发布校验未通过: route={} version={} 问题数={}",
                    routeId, version, result.issues().size());
            return result;
        }
        versionMapper.update(null, Wrappers.<CfgVersion>lambdaUpdate()
                .eq(CfgVersion::getId, v.getId())
                .set(CfgVersion::getStatus, "PUBLISHED")
                .set(CfgVersion::getPublishedAt, OffsetDateTime.now()));

        // 只有草稿版本在等发布，正好借这个时机把改动落进运行状态行
        configService.ensureConsumerState(routeId, version);
        log.info("版本已发布: route={} version={} 警告数={}", routeId, version,
                result.issues().size());
        return result;
    }

    @Transactional
    public void deleteVersion(long routeId, int version) {
        CfgRoute route = requireRoute(routeId);
        CfgVersion v = requireVersion(routeId, version);
        if (route.getActiveVersion() != null && route.getActiveVersion() == version) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "版本 %d 是当前生效版本，不可删除；请先激活其它版本".formatted(version));
        }
        if ("ACTIVE".equals(v.getStatus())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "版本 %d 处于 ACTIVE，不可删除".formatted(version));
        }
        versionMapper.deleteById(v.getId());
        log.info("配置版本已删除: route={} version={}", routeId, version);
    }

    /**
     * 回滚到当前生效版本之前最近的一个可激活版本。
     *
     * @return 被激活的版本号
     */
    @Transactional
    public int rollback(long routeId) {
        CfgRoute route = requireRoute(routeId);
        Integer current = route.getActiveVersion();
        if (current == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "该路由没有生效版本，无法回滚");
        }
        List<CfgVersion> candidates = versionMapper.selectList(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId)
                .lt(CfgVersion::getVersion, current)
                .in(CfgVersion::getStatus, List.of("PUBLISHED", "INACTIVE")));
        if (candidates.isEmpty()) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "没有可回滚的历史版本（当前版本 %d）".formatted(current));
        }
        candidates.sort(Comparator.comparing(CfgVersion::getVersion).reversed());
        int target = candidates.get(0).getVersion();
        // 复用 activate：它会处理旧版本降级、状态行与 AFTER_COMMIT 事件
        configService.activate(routeId, target);
        log.info("路由 {} 已从版本 {} 回滚到 {}", routeId, current, target);
        return target;
    }

    /**
     * 按目标表列自动生成映射草稿（PRD §36）。
     *
     * <p>同名精确匹配：目标列 {@code amount} ← 源 {@code $.amount}。
     * NOT NULL 且无默认值的列标记为 {@code required}，
     * 这样发布校验能顺带确认它们确实有来源。
     */
    @Transactional
    public DraftDto autoGenerateMappings(long routeId) {
        CfgRoute route = requireRoute(routeId);
        CfgTarget target = requireTarget(route.getTargetId());
        TargetRef ref = assembler.toTargetRef(target);
        TargetTableMeta meta = metadataReader.get(ref);

        List<Map<String, Object>> mappings = new ArrayList<>();
        for (ColumnMeta c : meta.columns().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", c.name());
            m.put("source", "$." + c.name());
            m.put("transform", Map.of("type", IntrospectionService.suggestTransform(c.pgType())));
            if (c.requiresValue()) {
                m.put("required", true);
            }
            mappings.add(m);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mappingStrategy", "EXACT");
        content.put("jslt", null);
        content.put("mappings", mappings);

        log.info("已按目标表列自动生成映射: route={} 列数={}", routeId, mappings.size());
        return saveDraft(routeId, new DraftRequest(content, "按目标表列自动生成"));
    }

    // ==================================================================
    // 内部：版本
    // ==================================================================

    private CfgVersion insertVersion(long routeId, int version, JsonNode content, String note) {
        CfgVersion v = new CfgVersion();
        v.setRouteId(routeId);
        v.setVersion(version);
        v.setStatus("DRAFT");
        v.setContent(content);
        v.setChangeNote(note);
        v.setCreatedBy("console");
        v.setCreatedAt(OffsetDateTime.now());
        versionMapper.insert(v);
        return v;
    }

    private int nextVersionNumber(long routeId) {
        List<CfgVersion> all = versionMapper.selectList(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId));
        return all.stream().map(CfgVersion::getVersion).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    private CfgVersion findDraft(long routeId) {
        List<CfgVersion> drafts = versionMapper.selectList(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getStatus, "DRAFT"));
        return drafts.isEmpty() ? null : drafts.get(0);
    }

    private CfgVersion findVersion(long routeId, int version) {
        return versionMapper.selectOne(Wrappers.<CfgVersion>lambdaQuery()
                .eq(CfgVersion::getRouteId, routeId)
                .eq(CfgVersion::getVersion, version));
    }

    private static boolean isEditable(String status) {
        return "DRAFT".equals(status) || "VALID".equals(status);
    }

    private DraftDto toDraft(long routeId, CfgVersion v, boolean persisted) {
        JsonNode content = v.getContent();
        return new DraftDto(routeId, v.getVersion(), v.getStatus(),
                JsonNodes.toPlain(content),
                JsonNodes.text(content, "jslt"),
                JsonNodes.text(content, "mappingStrategy", "EXACT"),
                mappingCount(content),
                v.getChangeNote(), v.getCreatedAt(), persisted);
    }

    private static int mappingCount(JsonNode content) {
        if (content == null) {
            return 0;
        }
        JsonNode m = content.path("mappings");
        return m.isArray() ? m.size() : 0;
    }

    private static Map<String, Object> emptyContent() {
        // LinkedHashMap 而非 Map.of：jslt 允许为 null，Map.of 不接受 null 值
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mappingStrategy", "EXACT");
        m.put("jslt", null);
        m.put("mappings", List.of());
        return m;
    }

    // ==================================================================
    // 内部：装配与校验
    // ==================================================================

    private DatasourceDto toDatasourceDto(CfgDatasource ds) {
        Long count = targetMapper.selectCount(
                Wrappers.<CfgTarget>lambdaQuery().eq(CfgTarget::getDatasourceId, ds.getId()));
        return new DatasourceDto(ds.getId(), ds.getName(), ds.getJdbcUrl(), ds.getUsername(),
                ds.getPasswordEnc() != null && !ds.getPasswordEnc().isBlank(),
                JsonNodes.toPlain(ds.getPoolConfig()),
                count == null ? 0L : count,
                ds.getCreatedAt(), ds.getUpdatedAt());
    }

    private TargetDto toTargetDto(CfgTarget t) {
        CfgDatasource ds = datasourceMapper.selectById(t.getDatasourceId());
        return new TargetDto(t.getId(), t.getName(), t.getDatasourceId(),
                ds == null ? null : ds.getName(),
                t.getSchemaName(), t.getTableName(), readKeys(t), t.getUpdateTimeField());
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

    private VersionSummaryDto toVersionSummary(CfgRoute route, CfgVersion v) {
        return new VersionSummaryDto(v.getVersion(), v.getStatus(), v.getChangeNote(),
                v.getCreatedBy(), v.getCreatedAt(), v.getPublishedAt(),
                route.getActiveVersion() != null && route.getActiveVersion().equals(v.getVersion()));
    }

    private List<String> readKeys(CfgTarget t) {
        JsonNode keys = t.getUpsertKeys();
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            return List.of("id");
        }
        List<String> out = new ArrayList<>(keys.size());
        keys.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---- 唯一性预检 ----
    //
    // 不依赖数据库唯一约束报错：那样只能得到 SQLSTATE 23505 与一句
    // 「duplicate key value violates unique constraint」，页面没法据此
    // 告诉用户是哪一条冲突。预检让错误信息可读，约束仍然是最后一道防线。

    private boolean nameTaken(String name, Long excludeId) {
        var q = Wrappers.<CfgDatasource>lambdaQuery().eq(CfgDatasource::getName, name.trim());
        if (excludeId != null) {
            q.ne(CfgDatasource::getId, excludeId);
        }
        return datasourceMapper.selectCount(q) > 0;
    }

    private boolean targetNameTaken(String name, Long excludeId) {
        var q = Wrappers.<CfgTarget>lambdaQuery().eq(CfgTarget::getName, name.trim());
        if (excludeId != null) {
            q.ne(CfgTarget::getId, excludeId);
        }
        return targetMapper.selectCount(q) > 0;
    }

    private boolean sameTableTaken(TargetRequest req, Long excludeId) {
        var q = Wrappers.<CfgTarget>lambdaQuery()
                .eq(CfgTarget::getDatasourceId, req.datasourceId())
                .eq(CfgTarget::getSchemaName, normalizeSchema(req.schemaName()))
                .eq(CfgTarget::getTableName, req.tableName().trim());
        if (excludeId != null) {
            q.ne(CfgTarget::getId, excludeId);
        }
        return targetMapper.selectCount(q) > 0;
    }

    private void checkRouteUnique(String topic, String tag, Long excludeId) {
        var q = Wrappers.<CfgRoute>lambdaQuery()
                .eq(CfgRoute::getTopic, topic.trim())
                .eq(CfgRoute::getTag, normalizeTag(tag));
        if (excludeId != null) {
            q.ne(CfgRoute::getId, excludeId);
        }
        if (routeMapper.selectCount(q) > 0) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "已存在消费同一 Topic+Tag 的路由: topic=%s tag=%s".formatted(topic, tag));
        }
    }

    // ---- 字段校验 ----

    private void requireDatasourceFields(DatasourceRequest req, boolean requirePassword) {
        if (req == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "请求体不能为空");
        }
        if (isBlank(req.name())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "数据源名称不能为空");
        }
        if (isBlank(req.jdbcUrl())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "JDBC URL 不能为空");
        }
        if (!req.jdbcUrl().trim().startsWith("jdbc:postgresql:")) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "只支持 PostgreSQL 数据源（MERGE RETURNING 与分区语法是 PG 专有）: " + req.jdbcUrl());
        }
        if (isBlank(req.username())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "用户名不能为空");
        }
        if (requirePassword && (req.password() == null || req.password().isEmpty())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "新建数据源必须提供密码");
        }
    }

    private void requireTargetFields(TargetRequest req) {
        if (req == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "请求体不能为空");
        }
        if (isBlank(req.name())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "目标表名称不能为空");
        }
        if (req.datasourceId() == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "必须选择数据源");
        }
        if (isBlank(req.tableName())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "表名不能为空");
        }
    }

    private void requireRouteFields(RouteRequest req) {
        if (req == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "请求体不能为空");
        }
        if (isBlank(req.name())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "路由名称不能为空");
        }
        if (isBlank(req.topic())) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "Topic 不能为空");
        }
        if (req.targetId() == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "必须选择目标表");
        }
    }

    // ---- require ----

    private CfgDatasource requireDatasource(Long id) {
        CfgDatasource ds = id == null ? null : datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "数据源不存在: " + id);
        }
        return ds;
    }

    private CfgTarget requireTarget(Long id) {
        CfgTarget t = id == null ? null : targetMapper.selectById(id);
        if (t == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "目标表定义不存在: " + id);
        }
        return t;
    }

    private CfgRoute requireRoute(long id) {
        CfgRoute r = routeMapper.selectById(id);
        if (r == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "路由不存在: " + id);
        }
        return r;
    }

    private CfgVersion requireVersion(long routeId, int version) {
        CfgVersion v = findVersion(routeId, version);
        if (v == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "配置版本不存在: routeId=%d version=%d".formatted(routeId, version));
        }
        return v;
    }

    // ---- 小工具 ----

    private static String targetNames(List<CfgTarget> targets) {
        return targets.stream().map(CfgTarget::getName).collect(Collectors.joining("、"));
    }

    private static List<String> normalizeKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of("id");
        }
        List<String> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                out.add(k.trim());
            }
        }
        // 全部是空白 → 退回默认，避免存出一个空数组让 upsert 无法生成
        return out.isEmpty() ? List.of("id") : List.copyOf(out);
    }

    private static String normalizeSchema(String schema) {
        return isBlank(schema) ? "public" : schema.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTag(String tag) {
        return tag == null ? "" : tag.trim();
    }

    private static String defaultIfBlank(String v, String fallback) {
        return isBlank(v) ? fallback : v.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
