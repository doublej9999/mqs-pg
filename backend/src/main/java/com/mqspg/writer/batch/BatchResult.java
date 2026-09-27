package com.mqspg.writer.batch;

import java.util.List;

/**
 * 一个批次的处置结果。
 *
 * @param dispositions 逐条消息的结论
 * @param aborted      是否因 PG 级故障或配置缺失中止
 * @param abortReason  中止原因
 */
public record BatchResult(List<MessageDisposition> dispositions, boolean aborted, String abortReason) {

    public BatchResult {
        dispositions = List.copyOf(dispositions);
    }

    public static BatchResult ok(List<MessageDisposition> dispositions) {
        return new BatchResult(dispositions, false, null);
    }

    public static BatchResult aborted(String reason, List<MessageDisposition> dispositions) {
        return new BatchResult(dispositions, true, reason);
    }

    public long count(Disposition kind) {
        return dispositions.stream().filter(d -> d.kind() == kind).count();
    }

    /**
     * 所有可以 ACK 的消息数（含正常写入、已落重试表、已入 DLQ）。
     *
     * <p>与 {@link #count(Disposition)} 的区别：后者按精确去向计数，
     * 本方法回答的是「有多少条可以安全 ACK」。
     */
    public long ackedCount() {
        return dispositions.stream().filter(d -> d.kind().shouldAck()).count();
    }

    /** 不得 ACK、需要 MQ 重新投递的消息数。 */
    public long unackedCount() {
        return dispositions.stream().filter(d -> !d.kind().shouldAck()).count();
    }

    public int total() {
        return dispositions.size();
    }
}
