package com.mqspg.writer.batch;

/** 单条消息的处理去向，决定是否 ACK。 */
public enum Disposition {

    /** 正常写入成功（INSERTED / UPDATED / SKIPPED_OLD_VERSION），可以 ACK。 */
    ACK(true),

    /** 已持久化重试任务，可以 ACK（ADR-02：重试行先落库，再 ACK）。 */
    ACK_DEFERRED(true),

    /** 已写入 DLQ 流水，可以 ACK。 */
    ACK_DLQ(true),

    /**
     * 不得 ACK。
     *
     * <p>两种情形：PG 级故障（让 MQ 重新投递），
     * 或重试任务落库失败（此时 ACK 会丢消息 —— 宁可重复也不能丢）。
     */
    NO_ACK(false);

    private final boolean ack;

    Disposition(boolean ack) {
        this.ack = ack;
    }

    public boolean shouldAck() {
        return ack;
    }
}
