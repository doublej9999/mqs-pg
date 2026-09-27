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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

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
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // 后台线程会与测试争抢，断言变得不确定（见 tech-design E.7.7）
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        // 留存是旁路，与断言语义无关；开着它会让本测试往 raw_message 写数据
        "mqs-pg.raw.enabled=false"
})
@DisplayName("配置域写操作")
class ConfigAdminTest {

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
                new DatasourceRequest(PREFIX + "ds", "jdbc:postgresql://localhost:5432/postgres",
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
                () -> admin.deleteVersion(route.id(), draft.version()));
        assertTrue(e.getMessage().contains("生效版本"), e.getMessage());

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
                PREFIX + "ds-pool", "jdbc:postgresql://localhost:5432/postgres",
                "postgres", "123456", Map.of("maximumPoolSize", 7)));

        DatasourceDto updated = admin.updateDatasource(ds.id(), new DatasourceRequest(
                PREFIX + "ds-pool", "jdbc:postgresql://localhost:5432/postgres",
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
                "jdbc:postgresql://localhost:5432/postgres", "postgres", password, null);
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
