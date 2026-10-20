package com.mqspg.config;

import com.mqspg.common.error.ProcessingException;
import com.mqspg.config.dto.ColumnDto;
import com.mqspg.config.dto.DatasourceDto;
import com.mqspg.config.dto.DatasourceRequest;
import com.mqspg.config.dto.DraftDto;
import com.mqspg.config.dto.DraftRequest;
import com.mqspg.config.dto.RouteRequest;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.TargetDto;
import com.mqspg.config.dto.TargetRequest;
import com.mqspg.config.dto.ValidationResultDto;
import com.mqspg.config.service.ConfigAdminService;
import com.mqspg.config.service.ConfigService;
import com.mqspg.config.service.IntrospectionService;
import com.mqspg.consumer.ConsumerManager;
import com.mqspg.support.AbstractPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置域写操作测试（阶段 9：页面配置化）。
 *
 * <p>打真实 PostgreSQL：写入路径包含 {@code MERGE} 前置校验、唯一约束、
 * 分区/CASCADE 语义，这些都无法用内存库替代。
 *
 * <p>用 {@code it-cfg-} 前缀隔离本测试创建的配置行，避免与 demo 种子数据
 * （datasource/target/route 各 id=1）以及其它测试互相干扰。
 */
@TestPropertySource(properties = {
        // 后台线程会与测试争抢，断言变得不确定（见 tech-design E.7.7）
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        // 留存是旁路，与断言语义无关；开着它会让本测试往 raw_message 写数据
        "mqs-pg.raw.enabled=false"
})
@DisplayName("配置域写操作")
class ConfigAdminTest extends AbstractPgIntegrationTest {

    private static final String PREFIX = "it-cfg-";
    private static final String TABLE = "cfg_admin_it";
    private static final String SCHEMA = "biz_demo";
    private static final String TOPIC = PREFIX + "topic";
    private static final String TAG = PREFIX + "tag";

    @Autowired
    private ConfigAdminService admin;
    @Autowired
    private ConfigService configService;
    @Autowired
    private IntrospectionService introspection;
    @Autowired
    private ConsumerManager consumerManager;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.%s (
                    id          BIGINT        NOT NULL PRIMARY KEY,
                    name        TEXT,
                    amount      NUMERIC(18,2),
                    update_time TIMESTAMPTZ   NOT NULL
                )
                """.formatted(SCHEMA, TABLE));
    }

    @AfterEach
    void tearDown() {
        purge();
        jdbc.execute("DROP TABLE IF EXISTS %s.%s".formatted(SCHEMA, TABLE));
    }

    // ==================================================================
    // 数据源
    // ==================================================================

    @Test
    @DisplayName("数据源增改查，且密码不出网、留空表示不改")
    void datasourceCrudHidesPassword() {
        DatasourceDto created = admin.createDatasource(dsRequest(PREFIX + "ds", "123456"));
        assertNotNull(created.id());
        assertTrue(created.hasPassword());

        // 落库的是带前缀的编码值，而不是明文
        String stored = jdbc.queryForObject(
                "SELECT password_enc FROM cfg_datasource WHERE id = ?", String.class, created.id());
        assertNotNull(stored);
        assertTrue(stored.startsWith("{noop}"), "应带 {noop} 前缀，实际: " + stored);
        assertEquals("{noop}123456", stored);

        // 列表里也找不到密码字段的痕迹
        List<DatasourceDto> all = admin.listDatasources();
        assertTrue(all.stream().anyMatch(d -> d.id().equals(created.id())));

        // 密码留空 → 保留原值
        admin.updateDatasource(created.id(),
                new DatasourceRequest(PREFIX + "ds", containerJdbcUrl(),
                        "postgres", "  ", null));
        assertEquals("{noop}123456", jdbc.queryForObject(
                "SELECT password_enc FROM cfg_datasource WHERE id = ?", String.class, created.id()));

        // 给了新密码 → 覆盖
        admin.updateDatasource(created.id(), dsRequest(PREFIX + "ds", "newpass"));
        assertEquals("{noop}newpass", jdbc.queryForObject(
                "SELECT password_enc FROM cfg_datasource WHERE id = ?", String.class, created.id()));
    }

    @Test
    @DisplayName("非 PostgreSQL 的 JDBC URL 被拒绝")
    void rejectsNonPostgresDatasource() {
        ProcessingException e = assertThrows(ProcessingException.class, () ->
                admin.createDatasource(new DatasourceRequest(PREFIX + "mysql",
                        "jdbc:mysql://localhost:3306/x", "root", "p", null)));
        assertTrue(e.getMessage().contains("PostgreSQL"), e.getMessage());
    }

    @Test
    @DisplayName("数据源被目标表引用时不可删除，且提示是谁在引用")
    void deleteDatasourceBlockedWhenReferenced() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds2", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt2", ds.id()));

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> admin.deleteDatasource(ds.id()));
        assertTrue(e.getMessage().contains(target.name()), e.getMessage());

        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
        assertEquals(0, datasourceRowCount(ds.id()).intValue());
    }

    // ==================================================================
    // 目标表
    // ==================================================================

    @Test
    @DisplayName("目标表被路由引用时不可删除")
    void deleteTargetBlockedWhenReferencedByRoute() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds3", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt3", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route3", TOPIC + "3", TAG, target.id()));

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> admin.deleteTarget(target.id()));
        assertTrue(e.getMessage().contains(route.name()), e.getMessage());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("元数据缓存随目标表修改失效")
    void updateTargetInvalidatesMetadata() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds4", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt4", ds.id()));

        List<ColumnDto> columns = introspection.listColumns(ds.id(), SCHEMA, TABLE);
        assertFalse(columns.isEmpty());
        assertTrue(columns.stream().anyMatch(c -> "update_time".equals(c.name())));

        // update_time 是 NOT NULL 且无默认值 → 必须由配置提供
        ColumnDto updateTime = columns.stream()
                .filter(c -> "update_time".equals(c.name())).findFirst().orElseThrow();
        assertTrue(updateTime.requiresValue());

        // id 是主键 → 建议的转换类型应来自列类型
        ColumnDto id = columns.stream()
                .filter(c -> "id".equals(c.name())).findFirst().orElseThrow();
        assertTrue(id.primaryKey());
        assertEquals("long", id.suggestedTransform());
    }

    // ==================================================================
    // 路由
    // ==================================================================

    @Test
    @DisplayName("新建路由为 INACTIVE 且无生效版本；同 Topic+Tag 冲突被拒")
    void routeCrudAndDuplicateRejected() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds5", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt5", ds.id()));

        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route5", TOPIC + "5", TAG, target.id()));
        assertEquals("INACTIVE", route.status());
        assertNull(route.activeVersion());
        assertEquals(TOPIC + "5", route.topic());

        ProcessingException e = assertThrows(ProcessingException.class, () ->
                admin.createRoute(new RouteRequest(PREFIX + "route5b", TOPIC + "5", TAG, target.id())));
        assertTrue(e.getMessage().contains("Topic"), e.getMessage());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    // ==================================================================
    // 草稿 → 发布 → 激活（本需求的核心闭环）
    // ==================================================================

    @Test
    @DisplayName("自动生成映射 → 发布 → 激活后，消费者无需重启即已启动")
    void activateStartsConsumerWithoutRestart() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds6", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt6", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route6", TOPIC + "6", TAG, target.id()));

        // 激活之前不应有消费者
        assertNull(consumerManager.consumerOf(route.id()));

        DraftDto draft = admin.autoGenerateMappings(route.id());
        assertTrue(draft.persisted());
        assertEquals(4, draft.mappingCount(), "表有 4 列，应生成 4 条映射");

        ValidationResultDto published = admin.publishVersion(route.id(), draft.version());
        assertTrue(published.valid(), () -> "发布校验未通过: " + published.issues());

        configService.activate(route.id(), draft.version());

        // ★ 本需求的核心回归点：不重启应用，消费者也起来了
        assertNotNull(consumerManager.consumerOf(route.id()),
                "激活后消费者应自动启动（AFTER_COMMIT 监听器）");
        assertEquals("ACTIVE", configService.listRoutes().stream()
                .filter(r -> r.id().equals(route.id())).findFirst().orElseThrow().status());

        // 删除路由后消费者应被停止
        admin.deleteRoute(route.id(), true);
        assertNull(consumerManager.consumerOf(route.id()));

        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("发布校验拦住 NOT NULL 无来源的列，并指明是哪一列")
    void publishRejectsUncoveredNotNullColumn() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds7", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt7", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route7", TOPIC + "7", TAG, target.id()));

        // 只映射 id，故意漏掉 NOT NULL 的 update_time
        Map<String, Object> content = Map.of(
                "mappingStrategy", "EXACT",
                "mappings", List.of(Map.of(
                        "target", "id",
                        "source", "$.id",
                        "transform", Map.of("type", "long"))));
        DraftDto draft = admin.saveDraft(route.id(), new DraftRequest(content, "缺列"));

        ValidationResultDto result = admin.validateVersion(route.id(), draft.version());
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i ->
                        "target.update_time".equals(i.field()) && "ERROR".equals(i.severity())),
                () -> "应报 update_time 无来源: " + result.issues());

        // 发布同样被拦下，且不改变状态
        ValidationResultDto publish = admin.publishVersion(route.id(), draft.version());
        assertFalse(publish.valid());
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), draft.version()));

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("JSLT 语法错误在发布时被拦下，而不是等第一条消息进 DLQ")
    void publishRejectsBrokenJslt() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-jslt", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-jslt", ds.id()));
        RouteSummaryDto route = admin.createRoute(new RouteRequest(
                PREFIX + "route-jslt", TOPIC + "jslt", TAG, target.id()));

        // 故意写坏：JSLT 不是 JSON，少了闭合括号，编译器必然报错
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mappingStrategy", "EXACT");
        content.put("jslt", "{\"a\": .b");
        content.put("mappings", List.of(Map.of(
                "target", "id",
                "source", "$.id",
                "transform", Map.of("type", "long"))));

        DraftDto draft = admin.saveDraft(route.id(), new DraftRequest(content, "坏 JSLT"));

        // 上一版 validateJslt 是死代码（全仓零调用者），坏脚本能一路通过发布、
        // 上线，直到第一条真实消息到达才以 JSLT_ERROR 进 DLQ
        ValidationResultDto publish = admin.publishVersion(route.id(), draft.version());
        assertFalse(publish.valid(), () -> "坏 JSLT 应阻止发布: " + publish.issues());
        assertTrue(publish.issues().stream().anyMatch(i ->
                        "jslt".equals(i.field()) && "ERROR".equals(i.severity())),
                () -> "应报 jslt 字段错误: " + publish.issues());

        // 且版本状态没变 —— 拦下就是拦下，不能「报错了但还是发布了」
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), draft.version()));

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("Upsert Key 没有唯一索引时发布失败")
    void publishRejectsUpsertKeyWithoutUniqueIndex() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds8", "123456"));
        // upsertKeys 用非唯一的 name 列
        TargetDto target = admin.createTarget(new TargetRequest(
                PREFIX + "tgt8", ds.id(), SCHEMA, TABLE, List.of("name"), "update_time"));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route8", TOPIC + "8", TAG, target.id()));

        Map<String, Object> content = Map.of(
                "mappingStrategy", "EXACT",
                "mappings", List.of(
                        Map.of("target", "name", "source", "$.name",
                                "transform", Map.of("type", "string")),
                        Map.of("target", "update_time", "source", "$.updatedAt",
                                "transform", Map.of("type", "timestamp"))));
        DraftDto draft = admin.saveDraft(route.id(), new DraftRequest(content, "非唯一 key"));

        ValidationResultDto result = admin.validateVersion(route.id(), draft.version());
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i ->
                        "upsertKeys".equals(i.field()) && i.message().contains("唯一索引")),
                () -> "应报缺少唯一索引: " + result.issues());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    // ==================================================================
    // 版本生命周期
    // ==================================================================

    @Test
    @DisplayName("当前生效版本不可删除")
    void cannotDeleteActiveVersion() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds9", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt9", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route9", TOPIC + "9", TAG, target.id()));
        DraftDto draft = admin.autoGenerateMappings(route.id());
        admin.publishVersion(route.id(), draft.version());
        configService.activate(route.id(), draft.version());

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> admin.deleteVersion(route.id(), draft.version(), false));
        assertTrue(e.getMessage().contains("生效版本"), e.getMessage());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("版本上还有未完成的重试任务时默认拒绝删除，force 才放行")
    void deleteVersionBlockedWhenRetryTasksBound() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-bd", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-bd", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-bd", TOPIC + "bd", TAG, target.id()));

        int v1 = admin.autoGenerateMappings(route.id()).version();
        admin.publishVersion(route.id(), v1);
        configService.activate(route.id(), v1);

        // 造一条绑在 v1 上的活跃重试任务（状态 PENDING 即「未完成」）
        jdbc.update("""
                INSERT INTO rt_retry_task
                    (route_id, config_version, message_id, topic, tag, error_stage, error_code,
                     attempt, max_attempt, next_retry_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'WRITE', 'PG_CONSTRAINT_VIOLATION',
                        0, 10, now(), 'PENDING', now(), now())
                """, route.id(), v1, PREFIX + "msg-bd", TOPIC + "bd", TAG);

        // 先把 v2 建出来并激活，让 v1 变成可删的 INACTIVE
        int v2 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v2);
        configService.activate(route.id(), v2);

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> admin.deleteVersion(route.id(), v1, false));
        assertEquals(com.mqspg.common.error.ErrorCode.CONFLICT, e.getCode(),
                "应抛 CONFLICT，前端据此弹「仍然删除」");
        assertTrue(e.getMessage().contains("重试任务"), e.getMessage());

        // force 放行
        admin.deleteVersion(route.id(), v1, true);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM cfg_version WHERE route_id = ? AND version = ?",
                Integer.class, route.id(), v1));

        jdbc.update("DELETE FROM rt_retry_task WHERE message_id = ?", PREFIX + "msg-bd");
        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("已激活的版本再调 publish 不会把它改回 PUBLISHED")
    void publishCannotDowngradeActiveVersion() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-pb", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-pb", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-pb", TOPIC + "pb", TAG, target.id()));

        int v1 = admin.autoGenerateMappings(route.id()).version();
        admin.publishVersion(route.id(), v1);
        configService.activate(route.id(), v1);

        // 迟到的 publish：状态已不是「可编辑」，应被拒绝而不是静默改回 PUBLISHED。
        // 若不拦，现役版本状态就不是 ACTIVE，deleteVersion 的 ACTIVE 保护随之失效。
        ProcessingException e = assertThrows(ProcessingException.class,
                () -> admin.publishVersion(route.id(), v1));
        assertTrue(e.getMessage().contains("不可发布"), e.getMessage());

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), v1),
                "activate 之后状态必须仍是 ACTIVE");

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("并发 publish 与 saveDraft：发布出去的内容必须是校验过的那一份")
    void concurrentPublishAndSaveDraftKeepsValidatedContent() throws Exception {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-ps", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-ps", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-ps", TOPIC + "ps", TAG, target.id()));
        int v1 = admin.autoGenerateMappings(route.id()).version();

        // 两个线程：一个反复 publish，一个反复 saveDraft 改 changeNote
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> publisher = pool.submit(() -> {
                start.await();
                try {
                    admin.publishVersion(route.id(), v1);
                } catch (ProcessingException expected) {
                    // CONFLICT（状态被并发改）也是合法结局
                }
                return null;
            });
            Future<?> saver = pool.submit(() -> {
                start.await();
                try {
                    admin.saveDraft(route.id(), new DraftRequest(validContent(), "并发保存"));
                } catch (ProcessingException expected) {
                    // 已被发布时，「版本不可编辑」是合法结局
                }
                return null;
            });
            start.countDown();
            publisher.get(30, TimeUnit.SECONDS);
            saver.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 核心断言：无论谁先到，PUBLISHED 版本的 change_note 不能是 saveDraft 写的那个 ——
        // 发布意味着「这一份内容通过了校验」，它不该被并发的草稿覆盖。
        String status = jdbc.queryForObject(
                "SELECT status FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), v1);
        String note = jdbc.queryForObject(
                "SELECT change_note FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), v1);
        if ("PUBLISHED".equals(status)) {
            assertTrue(!"并发保存".equals(note),
                    "已发布的内容不能被并发草稿覆盖（change_note 仍为「并发保存」）");
        } else {
            // saveDraft 赢了：版本仍是可编辑状态，内容自然可以是草稿
            assertTrue(isEditableStatus(status), "非 PUBLISHED 时应仍是可编辑状态: " + status);
        }

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    private static boolean isEditableStatus(String status) {
        return "DRAFT".equals(status) || "VALID".equals(status);
    }

    /** 一份能通过校验的完整内容（覆盖 id / name / update_time）。 */
    private static Map<String, Object> validContent() {
        return Map.of(
                "mappingStrategy", "EXACT",
                "mappings", List.of(
                        Map.of("target", "id", "source", "$.id",
                                "transform", Map.of("type", "long")),
                        Map.of("target", "name", "source", "$.name",
                                "transform", Map.of("type", "string")),
                        Map.of("target", "update_time", "source", "$.updatedAt",
                                "transform", Map.of("type", "timestamp"))));
    }

    @Test
    @DisplayName("激活新版本会更新运行状态行的 bind_version")
    void activateUpdatesBindVersion() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-bv", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-bv", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-bv", TOPIC + "bv", TAG, target.id()));

        int v1 = admin.autoGenerateMappings(route.id()).version();
        admin.publishVersion(route.id(), v1);
        configService.activate(route.id(), v1);
        assertEquals(v1, jdbc.queryForObject(
                "SELECT bind_version FROM rt_consumer_state WHERE route_id = ?",
                Integer.class, route.id()));

        int v2 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v2);
        configService.activate(route.id(), v2);

        // 上一版只在首次插入时写 bind_version，之后再激活永远不动 ——
        // 字段名与内容长期不一致，排查时会误导人
        assertEquals(v2, jdbc.queryForObject(
                "SELECT bind_version FROM rt_consumer_state WHERE route_id = ?",
                Integer.class, route.id()),
                "激活 v2 后 bind_version 必须跟着更新");

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("回滚会激活上一个已发布版本")
    void rollbackActivatesPreviousVersion() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds10", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt10", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route10", TOPIC + "10", TAG, target.id()));

        DraftDto v1 = admin.autoGenerateMappings(route.id());
        admin.publishVersion(route.id(), v1.version());
        configService.activate(route.id(), v1.version());

        // 从生效版本复制出 v2 并激活
        int v2 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v2);
        configService.activate(route.id(), v2);

        int rolledBack = admin.rollback(route.id());
        assertEquals(v1.version(), rolledBack);
        assertEquals(v1.version(), jdbc.queryForObject(
                "SELECT active_version FROM cfg_route WHERE id = ?", Integer.class, route.id()));

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    // ==================================================================
    // 并发保护（里程碑 G1 / M1）
    // ==================================================================

    @Test
    @DisplayName("并发激活两个版本，只会留下一个 ACTIVE")
    void concurrentActivateKeepsSingleActive() throws Exception {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-cc", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-cc", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-cc", TOPIC + "cc", TAG, target.id()));

        // 先发布并激活 v1：createVersion 是从**当前生效版本**复制内容的，
        // 若在激活之前建 v2，复制到的是空内容，发布校验必然不通过（草稿留在原地，
        // 后面的 createVersion 又会撞「已有草稿」）。
        int v1 = admin.autoGenerateMappings(route.id()).version();
        admin.publishVersion(route.id(), v1);
        configService.activate(route.id(), v1);

        // 再基于 v1 造两个已发布版本，它们都还没生效
        int v2 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v2);
        int v3 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v3);

        // 此刻 previous=1，两个线程会同时读到它 —— 这正是竞态的触发条件

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<?>> futures = new ArrayList<>();
        int[] targets = {v2, v3};
        try {
            for (int i = 0; i < threads; i++) {
                int version = targets[i];
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        configService.activate(route.id(), version);
                    } catch (ProcessingException expected) {
                        // 「状态已被并发修改」也是合法结局：关键是库里不能出现两个 ACTIVE
                    } finally {
                        done.countDown();
                    }
                    return null;
                }));
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发激活超时");
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 核心断言：任意时刻只有一个 ACTIVE 版本
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM cfg_version WHERE route_id = ? AND status = 'ACTIVE'",
                Integer.class, route.id()),
                "并发激活后 cfg_version 中 ACTIVE 版本必须恰好为 1");

        // active_version 也必须指向那个 ACTIVE 版本（不能指向已被降级的行）
        Integer activeVersion = jdbc.queryForObject(
                "SELECT active_version FROM cfg_route WHERE id = ?", Integer.class, route.id());
        assertNotNull(activeVersion);
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM cfg_version WHERE route_id = ? AND version = ?",
                String.class, route.id(), activeVersion),
                "cfg_route.active_version 指向的版本状态必须是 ACTIVE");

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("状态已被并发改动的版本不再被静默激活")
    void activateRejectsStatusChangedConcurrently() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-cf", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-cf", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-cf", TOPIC + "cf", TAG, target.id()));

        int v1 = admin.autoGenerateMappings(route.id()).version();
        admin.publishVersion(route.id(), v1);

        // 模拟「读到之后、写之前被别人改成了 DRAFT」：条件更新会拿到 0 行
        jdbc.update("UPDATE cfg_version SET status = 'DRAFT' WHERE route_id = ? AND version = ?",
                route.id(), v1);

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> configService.activate(route.id(), v1));
        assertTrue(e.getMessage().contains("不允许激活") || e.getMessage().contains("并发修改"),
                e.getMessage());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    // ==================================================================
    // JsonNode 字段的更新路径
    //
    // 这三个用例守着同一类缺陷：JsonNode 字段只有走实体上声明的
    // JsonbTypeHandler 才能落库。用 LambdaUpdateWrapper.set(字段, jsonNode)
    // 会把裸 JsonNode 交给 JDBC 驱动，PG 无法推断 SQL 类型而报
    // 「无法推测实例 ...ObjectNode 的 SQL 类型」。
    // ==================================================================

    @Test
    @DisplayName("更新数据源：非空 poolConfig 能落库，且留空密码时原密码保留")
    void updateDatasourceWritesPoolConfig() {
        DatasourceDto ds = admin.createDatasource(new DatasourceRequest(
                PREFIX + "ds-pool", containerJdbcUrl(),
                "postgres", "123456", Map.of("maximumPoolSize", 7)));

        DatasourceDto updated = admin.updateDatasource(ds.id(), new DatasourceRequest(
                PREFIX + "ds-pool", containerJdbcUrl(),
                "postgres", "   ", Map.of("maximumPoolSize", 9)));

        assertNotNull(updated.poolConfig(), "poolConfig 应被写入");
        String stored = jdbc.queryForObject(
                "SELECT pool_config FROM cfg_datasource WHERE id = ?", String.class, ds.id());
        assertTrue(stored.contains("9"), "池大小应更新为 9，实际: " + stored);
        assertEquals("{noop}123456", jdbc.queryForObject(
                "SELECT password_enc FROM cfg_datasource WHERE id = ?", String.class, ds.id()));

        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("更新目标表：upsert_keys 能落库")
    void updateTargetWritesUpsertKeys() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-tgtup", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgtup", ds.id()));

        TargetDto updated = admin.updateTarget(target.id(), new TargetRequest(
                PREFIX + "tgtup", ds.id(), SCHEMA, TABLE, List.of("id", "name"), "update_time"));

        assertEquals(List.of("id", "name"), updated.upsertKeys());
        assertEquals("update_time", updated.updateTimeField());
        String stored = jdbc.queryForObject(
                "SELECT upsert_keys FROM cfg_target WHERE id = ?", String.class, target.id());
        assertTrue(stored.contains("name"), "upsert_keys 应含 name，实际: " + stored);

        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    @Test
    @DisplayName("重复保存同一份草稿是覆盖，不是新建")
    void saveDraftTwiceOverwrites() {
        DatasourceDto ds = admin.createDatasource(dsRequest(PREFIX + "ds-draft", "123456"));
        TargetDto target = admin.createTarget(targetRequest(PREFIX + "tgt-draft", ds.id()));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route-draft", TOPIC + "draft", TAG, target.id()));

        DraftDto first = admin.saveDraft(route.id(), new DraftRequest(Map.of(
                "mappingStrategy", "EXACT",
                "mappings", List.of(Map.of("target", "id", "source", "$.id",
                        "transform", Map.of("type", "long")))), "第一次"));

        DraftDto second = admin.saveDraft(route.id(), new DraftRequest(Map.of(
                "mappingStrategy", "EXACT",
                "mappings", List.of(Map.of("target", "name", "source", "$.name",
                        "transform", Map.of("type", "string")))), "第二次"));

        assertEquals(first.version(), second.version(), "应覆盖同一个草稿版本");
        assertEquals(1, second.mappingCount());
        assertEquals("第二次", second.changeNote());
        assertTrue(second.persisted());

        admin.deleteRoute(route.id(), true);
        admin.deleteTarget(target.id());
        admin.deleteDatasource(ds.id());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private DatasourceRequest dsRequest(String name, String password) {
        return new DatasourceRequest(name,
                containerJdbcUrl(), "postgres", password, null);
    }

    private TargetRequest targetRequest(String name, Long datasourceId) {
        return new TargetRequest(name, datasourceId, SCHEMA, TABLE, List.of("id"), "update_time");
    }

    private Integer datasourceRowCount(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM cfg_datasource WHERE id = ?", Integer.class, id);
    }

    /** 只清本测试造的行，按外键顺序：路由（级联版本/状态）→ 目标表 → 数据源。 */
    private void purge() {
        List<Long> routeIds = jdbc.queryForList(
                "SELECT id FROM cfg_route WHERE name LIKE ?", Long.class, PREFIX + "%");
        for (Long routeId : routeIds) {
            // 停掉可能已被激活的消费者，否则会留下持有已删配置的线程
            consumerManager.stop(routeId, "测试清理");
        }
        jdbc.update("DELETE FROM cfg_route WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM cfg_target WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM cfg_datasource WHERE name LIKE ?", PREFIX + "%");
    }
}
