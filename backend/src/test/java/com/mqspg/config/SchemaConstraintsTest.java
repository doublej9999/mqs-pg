package com.mqspg.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.mqspg.support.AbstractPgIntegrationTest;

/**
 * V2 迁移的约束测试（里程碑 G4 / I7）。
 *
 * <p>这些约束的价值全在「库层拦住拼错的值」——用服务层调用测不出来，
 * 因为服务层本来就不会写错，所以这里直接用裸 SQL 打库。
 */
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        "mqs-pg.raw.enabled=false"
})
@DisplayName("V2 约束与索引")
class SchemaConstraintsTest extends AbstractPgIntegrationTest {

    private static final String PREFIX = "it-ck-";

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // I7：状态列 CHECK
    // ------------------------------------------------------------------

    @Test
    @DisplayName("路由状态拼错会被数据库拒绝，而不是静默漏掉该行")
    void routeStatusTypoRejected() {
        // RUNNIG 少一个 N：拼错的状态会让按 status='RUNNING' 的查询静默漏掉它，
        // 表现是「路由配了但不消费」——本项目最怕的静默失败类型。
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "INSERT INTO cfg_route (name, topic, tag, target_id, status) "
                                + "VALUES (?, ?, ?, 1, 'RUNNIG')",
                        PREFIX + "typo", PREFIX + "topic", PREFIX + "tag"));
        assertTrue(String.valueOf(e.getMostSpecificCause().getMessage()).contains("ck_cfg_route_status"),
                () -> "应撞 ck_cfg_route_status，实际: " + e.getMostSpecificCause().getMessage());
    }

    @Test
    @DisplayName("版本 / 重试任务 / 消费状态的状态列同样被约束")
    void otherStatusColumnsRejected() {
        // 自建一条路由，保证下面的 UPDATE/INSERT 一定能命中行 ——
        // UPDATE 影响 0 行时不会触发 CHECK，那样测出来的是「什么也没发生」
        jdbc.update("INSERT INTO cfg_route (id, name, topic, tag, target_id, status) "
                + "VALUES (999002, ?, ?, ?, 1, 'INACTIVE')",
                PREFIX + "ck", PREFIX + "topic", PREFIX + "tag");
        jdbc.update("INSERT INTO cfg_version (route_id, version, status, content) "
                + "VALUES (999002, 1, 'DRAFT', '{}'::jsonb)");

        try {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "UPDATE cfg_version SET status = 'PUBLISHD' WHERE route_id = 999002 AND version = 1"));

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO rt_retry_task
                        (route_id, config_version, message_id, topic, error_stage, error_code, status)
                    VALUES (999002, 1, ?, 't', 'WRITE', 'PG_UNKNOWN', 'PENDNG')
                    """, PREFIX + "msg"));

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO rt_consumer_state (route_id, status)
                    VALUES (999002, 'RUNNIG')
                    """));
        } finally {
            jdbc.update("DELETE FROM rt_retry_task WHERE message_id = ?", PREFIX + "msg");
            jdbc.update("DELETE FROM rt_consumer_state WHERE route_id = 999002");
            jdbc.update("DELETE FROM cfg_route WHERE id = 999002");
        }
    }

    // ------------------------------------------------------------------
    // G4：每个路由至多一个草稿
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一路由不能有两个 DRAFT 版本（部分唯一索引）")
    void onlyOneDraftPerRoute() {
        // 路由 1 是 dev 种子里的 demo 路由；先确保它没有草稿，再手工插两条
        jdbc.update("DELETE FROM cfg_version WHERE route_id = 1 AND status = 'DRAFT'");
        int base = jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) FROM cfg_version WHERE route_id = 1", Integer.class);

        jdbc.update("INSERT INTO cfg_version (route_id, version, status, content) "
                + "VALUES (1, ?, 'DRAFT', '{}'::jsonb)", base + 1);

        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO cfg_version (route_id, version, status, content) "
                        + "VALUES (1, ?, 'DRAFT', '{}'::jsonb)", base + 2));
        assertTrue(String.valueOf(e.getMostSpecificCause().getMessage()).contains("uk_version_draft"),
                () -> "应撞 uk_version_draft，实际: " + e.getMostSpecificCause().getMessage());

        // 但非 DRAFT 可以有多行 —— 索引是部分的，别把正常版本也限死
        jdbc.update("INSERT INTO cfg_version (route_id, version, status, content) "
                + "VALUES (1, ?, 'PUBLISHED', '{}'::jsonb)", base + 2);
        jdbc.update("INSERT INTO cfg_version (route_id, version, status, content) "
                + "VALUES (1, ?, 'INACTIVE', '{}'::jsonb)", base + 3);

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM cfg_version WHERE route_id = 1 AND status = 'DRAFT'",
                Integer.class));

        // 清理：路由 1 的版本是种子数据，别污染其它测试
        jdbc.update("DELETE FROM cfg_version WHERE route_id = 1 AND version >= ?", base + 1);
    }

    // ------------------------------------------------------------------
    // J6：错误流水索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("错误流水的 (is_final, id) 与 (error_stage, id) 索引存在")
    void errorRecordIndexesExist() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'mqs_pg'
                  AND indexname IN ('idx_error_final_id', 'idx_error_stage_id')
                """, Integer.class);
        assertEquals(2, count);
    }
}
