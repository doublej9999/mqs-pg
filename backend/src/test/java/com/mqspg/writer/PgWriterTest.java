package com.mqspg.writer;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRow;
import com.mqspg.config.service.ConfigService;
import com.mqspg.mqs.FakeMessage;
import com.mqspg.writer.batch.BufferedMessage;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import com.mqspg.writer.fold.BatchFolder;
import com.mqspg.writer.fold.CandidateRecord;
import com.mqspg.writer.fold.FoldedRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG Writer 集成测试，直接打真实 PostgreSQL（tech-design §18）。
 *
 * <p>验证的是设计里最容易出错、也最不能出错的部分：
 * <ol>
 *   <li>{@code update_time} 单调守卫是**严格大于**（PRD §7）；</li>
 *   <li>{@code SKIPPED_OLD_VERSION} 能由「输入键集 − RETURNING 键集」正确推导；</li>
 *   <li>同批次重复 Key 折叠后不触发 PG 的重复冲突键错误；</li>
 *   <li>数据级错误能被二分隔离到具体行，不阻塞同批次的好行（PRD §49）。</li>
 * </ol>
 *
 * <p>依赖：本地 docker PostgreSQL 与 dev profile 的 demo 种子数据（路由 1 / biz_demo.orders）。
 */
@SpringBootTest
@ActiveProfiles("dev")
class PgWriterTest {

    private static final long TEST_ID_1 = 990_001L;
    private static final long TEST_ID_2 = 990_002L;
    private static final long TEST_ID_3 = 990_003L;
    private static final List<Long> TEST_IDS = List.of(TEST_ID_1, TEST_ID_2, TEST_ID_3);

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2024-01-03T00:00:00Z");

    @Autowired
    private PgWriter writer;

    @Autowired
    private ConfigService configService;

    @Autowired
    private BatchFolder folder;

    @Autowired
    private TargetDataSourceRegistry dataSources;

    @AfterEach
    void cleanUp() {
        JdbcTemplate jdbc = targetJdbc();
        for (Long id : TEST_IDS) {
            jdbc.update("DELETE FROM biz_demo.orders WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("INSERTED → 同 update_time 跳过 → 更旧跳过 → 更新，严格大于守卫")
    void insertThenSkipThenUpdate() {
        RouteConfig cfg = activeConfig();

        MergeResult r1 = writer.write(cfg, fold(rec(TEST_ID_1, T1, "v1")));
        assertThat(r1.aborted()).isFalse();
        assertThat(r1.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.INSERTED);

        // PRD §7：incoming.update_time <= existing.update_time → SKIP。
        // 与已有行 update_time 完全相同时必须跳过，而不是更新。
        MergeResult r2 = writer.write(cfg, fold(rec(TEST_ID_1, T1, "dup-same-time")));
        assertThat(r2.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.SKIPPED_OLD_VERSION);

        MergeResult r3 = writer.write(cfg, fold(rec(TEST_ID_1, T0, "older")));
        assertThat(r3.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.SKIPPED_OLD_VERSION);

        MergeResult r4 = writer.write(cfg, fold(rec(TEST_ID_1, T2, "v2")));
        assertThat(r4.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.UPDATED);

        // 落库值必须是 v2，任何一次乱序写入都不得污染它
        assertThat(nameOf(TEST_ID_1)).isEqualTo("v2");
        assertThat(updateTimeOf(TEST_ID_1).toInstant()).isEqualTo(T2);
    }

    @Test
    @DisplayName("同批次同 Key 多条：折叠后只写一行，不触发 PG 重复冲突键错误")
    void duplicateKeysInOneBatchAreFolded() {
        RouteConfig cfg = activeConfig();

        List<CandidateRecord> input = List.of(
                rec(TEST_ID_1, T1, "a"),
                rec(TEST_ID_1, T2, "b"),
                rec(TEST_ID_1, T0, "c"));

        List<FoldedRecord> folded = folder.fold(input, cfg.upsertKeys(), cfg.updateTimeField());
        assertThat(folded).hasSize(1);

        MergeResult result = writer.write(cfg, folded);

        assertThat(result.aborted()).isFalse();
        assertThat(result.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.INSERTED);
        assertThat(nameOf(TEST_ID_1)).isEqualTo("b");
        // 三条来源都要能被 ACK
        assertThat(folded.get(0).sources()).hasSize(3);
    }

    @Test
    @DisplayName("多行批次：逐行结果分别正确")
    void mixedResultsAcrossRows() {
        RouteConfig cfg = activeConfig();

        // 先建两行
        writer.write(cfg, fold(rec(TEST_ID_1, T1, "one"), rec(TEST_ID_2, T1, "two")));

        // 一行更新、一行跳过、一行新增
        MergeResult result = writer.write(cfg, fold(
                rec(TEST_ID_1, T2, "one-new"),
                rec(TEST_ID_2, T0, "two-stale"),
                rec(TEST_ID_3, T1, "three-new")));

        assertThat(result.aborted()).isFalse();
        assertThat(result.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.UPDATED);
        assertThat(result.actionOf(key(TEST_ID_2))).isEqualTo(MergeAction.SKIPPED_OLD_VERSION);
        assertThat(result.actionOf(key(TEST_ID_3))).isEqualTo(MergeAction.INSERTED);
        assertThat(result.failedKeys()).isEmpty();

        assertThat(nameOf(TEST_ID_1)).isEqualTo("one-new");
        assertThat(nameOf(TEST_ID_2)).isEqualTo("two");
    }

    @Test
    @DisplayName("数据级错误被二分隔离，不阻塞同批次的好行（PRD §49）")
    void badRowIsIsolatedAndDoesNotBlockGoodRows() {
        RouteConfig cfg = activeConfig();

        List<CandidateRecord> input = new ArrayList<>();
        input.add(rec(TEST_ID_1, T1, "good-1"));
        // status 列是 INTEGER，传非数字串会让 ?::integer 抛 22P02
        input.add(badRec(TEST_ID_2, T1));
        input.add(rec(TEST_ID_3, T1, "good-2"));

        List<FoldedRecord> folded = folder.fold(input, cfg.upsertKeys(), cfg.updateTimeField());
        assertThat(folded).hasSize(3);

        MergeResult result = writer.write(cfg, folded);

        assertThat(result.aborted()).isFalse();
        assertThat(result.failedKeys()).containsExactly(key(TEST_ID_2));
        assertThat(result.actionOf(key(TEST_ID_1))).isEqualTo(MergeAction.INSERTED);
        assertThat(result.actionOf(key(TEST_ID_3))).isEqualTo(MergeAction.INSERTED);
        // 坏行没有产生 SKIPPED 兜底，也不会被误判为成功
        assertThat(result.actions()).doesNotContainKey(key(TEST_ID_2));

        // 好行确实落库了
        assertThat(nameOf(TEST_ID_1)).isEqualTo("good-1");
        assertThat(nameOf(TEST_ID_3)).isEqualTo("good-2");
        assertThat(nameOf(TEST_ID_2)).isNull();
    }

    // ------------------------------------------------------------------

    private RouteConfig activeConfig() {
        return configService.resolve(1L, 1);
    }

    private JdbcTemplate targetJdbc() {
        return dataSources.jdbc(activeConfig().target().datasourceId());
    }

    private String nameOf(long id) {
        List<String> r = targetJdbc().queryForList(
                "SELECT name FROM biz_demo.orders WHERE id = ?", String.class, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private OffsetDateTime updateTimeOf(long id) {
        List<OffsetDateTime> r = targetJdbc().queryForList(
                "SELECT update_time FROM biz_demo.orders WHERE id = ?", OffsetDateTime.class, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private static KeyTuple key(long id) {
        return KeyTuple.of(List.of(id));
    }

    private static CandidateRecord rec(long id, Instant ut, String name) {
        TargetRow row = new TargetRow()
                .put("id", id)
                .put("name", name)
                .put("amount", new BigDecimal("10.00"))
                .put("status", 1)
                .put("source", "TEST")
                .put("update_time", ut.atOffset(ZoneOffset.UTC));
        return new CandidateRecord(
                new BufferedMessage(new FakeMessage("m-" + id + "-" + ut, "{}"), 1, Instant.now()), row);
    }

    private static CandidateRecord badRec(long id, Instant ut) {
        TargetRow row = new TargetRow()
                .put("id", id)
                .put("name", "bad")
                .put("amount", new BigDecimal("10.00"))
                .put("status", "NOT-AN-INTEGER")
                .put("source", "TEST")
                .put("update_time", ut.atOffset(ZoneOffset.UTC));
        return new CandidateRecord(
                new BufferedMessage(new FakeMessage("m-bad-" + id, "{}"), 1, Instant.now()), row);
    }

    private static List<FoldedRecord> fold(CandidateRecord... records) {
        BatchFolder f = new BatchFolder();
        return f.fold(List.of(records), List.of("id"), "update_time");
    }
}
