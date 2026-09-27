package com.mqspg.writer.fold;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.TargetRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 折叠结果：一个 Upsert Key 对应一条最终写入行。
 *
 * @param key        Upsert Key
 * @param winnerRow  最终进入 SQL 的那一行
 * @param sources    该 Key 下**所有**原始消息（含被淘汰的），用于 ACK 归属
 */
public record FoldedRecord(KeyTuple key, TargetRow winnerRow, List<MessageRef> sources) {

    public FoldedRecord {
        sources = List.copyOf(sources);
    }

    public static FoldedRecord first(KeyTuple key, CandidateRecord c) {
        return new FoldedRecord(key, c.row(), List.of(c.ref()));
    }

    /** 用更新的记录替换 winner，同时把新记录计入 sources。 */
    public FoldedRecord withWinner(CandidateRecord c) {
        List<MessageRef> merged = new ArrayList<>(sources);
        merged.add(c.ref());
        return new FoldedRecord(key, c.row(), merged);
    }

    /** 保留 winner，仅追加来源（该记录更旧，将被 SKIP）。 */
    public FoldedRecord addSource(CandidateRecord c) {
        List<MessageRef> merged = new ArrayList<>(sources);
        merged.add(c.ref());
        return new FoldedRecord(key, winnerRow, merged);
    }

    /** 该 Key 被折叠掉的消息数（用于监控「折叠率」）。 */
    public int foldedAwayCount() {
        return sources.size() - 1;
    }
}
