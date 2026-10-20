package com.mqspg.writer.fold;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.TargetRow;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次内同 Upsert Key 保序折叠（tech-design §7.3 / ADR-05）。
 *
 * <p><b>为什么必须折叠</b>：PostgreSQL 明确要求 MERGE 的源对每个目标行最多产生一个候选行。
 * 同一冲突键在一条语句中出现多次会直接报错（唯一约束冲突或基数违规）。
 * 因此 PRD §10 描述的「A→B→C 三次操作」无法用单条多值 SQL 表达，必须先在应用层归并。
 *
 * <p><b>折叠规则</b>：
 * <ol>
 *   <li>{@code update_time} 最大者胜出；</li>
 *   <li>{@code update_time} 相同时，以**最后出现**的为准（PRD §8：以 MQ 消费顺序为准）；</li>
 *   <li>更旧的记录被淘汰，但**仍计入 sources** —— 否则这些消息永远不会被 ACK，
 *       会被 MQ 无限重投。</li>
 * </ol>
 *
 * <p><b>复杂度</b>：O(n)，单次哈希查找。
 */
@Component
public class BatchFolder {

    /**
     * @param candidates      已成功转换的候选记录，**顺序必须等于 MQ 消费顺序**
     * @param upsertKeys      Upsert Key 列名
     * @param updateTimeField 单调守卫字段名
     */
    public List<FoldedRecord> fold(List<CandidateRecord> candidates,
                                   List<String> upsertKeys,
                                   String updateTimeField) {
        // LinkedHashMap 保持首次出现顺序，便于日志与调试
        Map<KeyTuple, FoldedRecord> acc = new LinkedHashMap<>(Math.max(16, candidates.size()));

        for (CandidateRecord c : candidates) {
            // 规范化键值：BigDecimal 的 equals 对 scale 敏感（1.0 != 1.00），
            // 不规范化会让同一个 Key 被拆成两条，进而触发 PG 的重复键错误。
            KeyTuple key = KeyTuple.ofNormalized(c.row().keyValues(upsertKeys));

            FoldedRecord prev = acc.get(key);
            if (prev == null) {
                acc.put(key, FoldedRecord.first(key, c));
                continue;
            }

            int cmp = compareUpdateTime(c.row(), prev.winnerRow(), updateTimeField);
            if (cmp >= 0) {
                // cmp > 0 : 更新的记录胜出
                // cmp == 0: 相同 update_time，以最后出现的为准（PRD §8）
                acc.put(key, prev.withWinner(c));
            } else {
                // 更旧的记录：保留 winner，仅追加来源以便 ACK
                acc.put(key, prev.addSource(c));
            }
        }

        return new ArrayList<>(acc.values());
    }

    /**
     * 与 {@link #fold} 同一套规则，但候选只有目标行、没有来源消息。
     *
     * <p>供重试路径使用：那里消息早已 ACK，不该构造 {@link CandidateRecord}，
     * 也不该让 {@code FoldedRecord.sources()} 非空（重放路径依赖它为空）。
     *
     * <p>折叠规则与 {@link #fold} **完全一致**（同键取 update_time 最大者，
     * 相同时取最后出现者）。之所以不复制一份比较逻辑，是因为两处一旦漂移，
     * 同一条消息走实时路径和重试路径会得到不同的胜出者 —— 那是最难查的一类不一致。
     *
     * @param candidates      待折叠的行，**顺序必须等于原始处理顺序**
     * @param upsertKeys      Upsert Key 列名
     * @param updateTimeField 单调守卫字段名
     * @return 每个 Upsert Key 一条最终行（sources 恒为空）
     */
    public List<FoldedRecord> foldRows(List<CandidateRow> candidates,
                                       List<String> upsertKeys,
                                       String updateTimeField) {
        Map<KeyTuple, FoldedRecord> acc = new LinkedHashMap<>(Math.max(16, candidates.size()));
        for (CandidateRow c : candidates) {
            KeyTuple key = KeyTuple.ofNormalized(c.row().keyValues(upsertKeys));
            FoldedRecord prev = acc.get(key);
            if (prev == null) {
                acc.put(key, new FoldedRecord(key, c.row(), List.of()));
                continue;
            }
            if (compareUpdateTime(c.row(), prev.winnerRow(), updateTimeField) >= 0) {
                acc.put(key, new FoldedRecord(key, c.row(), List.of()));
            }
            // 更旧的记录：保留 winner，也不需要追加来源（没有来源可追加）
        }
        return new ArrayList<>(acc.values());
    }

    /** null 视为最小。 */
    static int compareUpdateTime(TargetRow a, TargetRow b, String field) {
        Instant ta = a.getInstant(field);
        Instant tb = b.getInstant(field);
        if (ta == null && tb == null) {
            return 0;
        }
        if (ta == null) {
            return -1;
        }
        if (tb == null) {
            return 1;
        }
        return ta.compareTo(tb);
    }
}
