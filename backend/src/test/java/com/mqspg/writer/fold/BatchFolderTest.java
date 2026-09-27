package com.mqspg.writer.fold;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.TargetRow;
import com.mqspg.mqs.FakeMessage;
import com.mqspg.writer.batch.BufferedMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 折叠算法测试（tech-design §7.3 / ADR-05）。
 *
 * <p>折叠正确性直接决定两件事：MERGE 会不会因重复冲突键报错，
 * 以及被淘汰的消息会不会被 ACK。
 */
class BatchFolderTest {

    private static final List<String> KEYS = List.of("id");
    private static final String UT = "update_time";

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2024-01-03T00:00:00Z");

    private final BatchFolder folder = new BatchFolder();

    @Test
    @DisplayName("同一 Key 多条：保留 update_time 最大者，且所有来源都被收集")
    void keepsNewestAndCollectsAllSources() {
        List<CandidateRecord> input = List.of(
                candidate("1", T1, "v1"),
                candidate("1", T2, "v2"),
                candidate("1", T0, "v0"));

        List<FoldedRecord> folded = folder.fold(input, KEYS, UT);

        assertThat(folded).hasSize(1);
        FoldedRecord r = folded.get(0);
        assertThat(r.winnerRow().get("name")).isEqualTo("v2");
        // 三条消息全部登记，否则被淘汰的两条永远不会被 ACK，会被 MQ 无限重投
        assertThat(r.sources()).hasSize(3);
        assertThat(r.foldedAwayCount()).isEqualTo(2);
        assertThat(r.sources()).extracting(MessageRef::messageId)
                .containsExactlyInAnyOrder("m-1-" + T1 + "-v1", "m-1-" + T2 + "-v2", "m-1-" + T0 + "-v0");
    }

    @Test
    @DisplayName("update_time 相同时以最后出现者为准（PRD §8：以消费顺序为准）")
    void tieKeepsLastInConsumptionOrder() {
        List<CandidateRecord> input = List.of(
                candidate("1", T1, "first"),
                candidate("1", T1, "second"),
                candidate("1", T1, "third"));

        List<FoldedRecord> folded = folder.fold(input, KEYS, UT);

        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).winnerRow().get("name")).isEqualTo("third");
        assertThat(folded.get(0).sources()).hasSize(3);
    }

    @Test
    @DisplayName("更旧的消息后到：不得覆盖已胜出的新版本")
    void olderArrivingLaterDoesNotWin() {
        List<CandidateRecord> input = List.of(
                candidate("1", T2, "new"),
                candidate("1", T0, "stale"));

        List<FoldedRecord> folded = folder.fold(input, KEYS, UT);

        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).winnerRow().get("name")).isEqualTo("new");
    }

    @Test
    @DisplayName("不同 Key 各成一条，且输出保持首次出现顺序")
    void preservesFirstSeenOrderAcrossKeys() {
        List<CandidateRecord> input = List.of(
                candidate("2", T1, "a"),
                candidate("1", T1, "b"),
                candidate("3", T1, "c"));

        List<FoldedRecord> folded = folder.fold(input, KEYS, UT);

        assertThat(folded).extracting(f -> f.key().values().get(0))
                .containsExactly(2L, 1L, 3L);
    }

    @Test
    @DisplayName("Key 值规范化：Byte/Integer/Long 与 BigDecimal 尾零不影响折叠")
    void normalizesKeyValues() {
        TargetRow byInteger = new TargetRow().put("id", 1).put(UT, T1).put("name", "int");
        TargetRow byLong = new TargetRow().put("id", 1L).put(UT, T1).put("name", "long");

        List<CandidateRecord> input = List.of(
                new CandidateRecord(buffered("a"), byInteger),
                new CandidateRecord(buffered("b"), byLong));

        List<FoldedRecord> folded = folder.fold(input, KEYS, UT);

        // 若不规范化，Integer 1 与 Long 1 会是两个不同的 Key，导致同一条 SQL 中
        // 出现两个相同目标行 —— PG 会直接报唯一约束冲突
        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).sources()).hasSize(2);
    }

    @Test
    @DisplayName("Decimal Key 尾零：1.0 与 1.00 必须折叠为同一条")
    void normalizesBigDecimalScaleInKey() {
        TargetRow a = new TargetRow().put("id", new BigDecimal("1.0")).put(UT, T1).put("name", "a");
        TargetRow b = new TargetRow().put("id", new BigDecimal("1.00")).put(UT, T1).put("name", "b");

        List<FoldedRecord> folded = folder.fold(
                List.of(new CandidateRecord(buffered("a"), a),
                        new CandidateRecord(buffered("b"), b)),
                KEYS, UT);

        assertThat(folded).hasSize(1);
    }

    @Test
    @DisplayName("空输入返回空结果")
    void emptyInput() {
        assertThat(folder.fold(List.of(), KEYS, UT)).isEmpty();
    }

    // ------------------------------------------------------------------

    private static CandidateRecord candidate(String id, Instant ut, String name) {
        TargetRow row = new TargetRow()
                .put("id", Long.parseLong(id))
                .put("name", name)
                .put(UT, ut);
        return new CandidateRecord(buffered("m-" + id + "-" + ut + "-" + name), row);
    }

    private static BufferedMessage buffered(String messageId) {
        return new BufferedMessage(new FakeMessage(messageId, "{}"), 1, Instant.now());
    }

    /** 便于阅读的 Key 构造。 */
    @SuppressWarnings("unused")
    private static KeyTuple key(long id) {
        return KeyTuple.of(List.of(id));
    }
}
