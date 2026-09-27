package com.mqspg.writer;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次批次写入的结果。
 *
 * @param actions    Upsert Key → 逐行结果（含推导出的 SKIPPED_OLD_VERSION）
 * @param failedKeys 已隔离出来的失败行（不 ACK，进重试表）
 * @param aborted    是否因 PG 级故障中止（此时未处理的消息既不 ACK 也不进重试表，
 *                   由 MQ 重新投递）
 * @param cause      中止原因
 */
public record MergeResult(
        Map<KeyTuple, MergeAction> actions,
        Set<KeyTuple> failedKeys,
        boolean aborted,
        Throwable cause) {

    public MergeResult {
        actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
        failedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(failedKeys));
    }

    public static MergeResult ok(Map<KeyTuple, MergeAction> actions, Set<KeyTuple> failed) {
        return new MergeResult(actions, failed, false, null);
    }

    public static MergeResult aborted(Throwable cause,
                                      Map<KeyTuple, MergeAction> actions,
                                      Set<KeyTuple> failed) {
        return new MergeResult(actions, failed, true, cause);
    }

    public MergeAction actionOf(KeyTuple key) {
        return actions.get(key);
    }
}
