package com.mqspg.writer;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一次批次写入的结果。
 *
 * @param actions    Upsert Key → 逐行结果（含推导出的 SKIPPED_OLD_VERSION）
 * @param failures   已隔离出来的失败行 → 失败原因；这些行不 ACK，进重试表
 * @param aborted    是否因 PG 级故障中止（此时未处理的消息既不 ACK 也不进重试表，
 *                   交由 MQ 重新投递）
 * @param cause      中止原因
 */
public record MergeResult(
        Map<KeyTuple, MergeAction> actions,
        Map<KeyTuple, String> failures,
        boolean aborted,
        Throwable cause) {

    public MergeResult {
        actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
        failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public static MergeResult ok(Map<KeyTuple, MergeAction> actions, Map<KeyTuple, String> failures) {
        return new MergeResult(actions, failures, false, null);
    }

    public static MergeResult aborted(Throwable cause,
                                      Map<KeyTuple, MergeAction> actions,
                                      Map<KeyTuple, String> failures) {
        return new MergeResult(actions, failures, true, cause);
    }

    public MergeAction actionOf(KeyTuple key) {
        return actions.get(key);
    }

    public boolean hasFailure(KeyTuple key) {
        return failures.containsKey(key);
    }

    public Set<KeyTuple> failedKeys() {
        return failures.keySet();
    }

    public String failureOf(KeyTuple key) {
        return failures.get(key);
    }
}
