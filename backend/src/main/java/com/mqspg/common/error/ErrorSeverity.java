package com.mqspg.common.error;

/**
 * 错误严重级别，决定消息去向（tech-design.md §8.6）。
 */
public enum ErrorSeverity {

    /** 数据本身问题，重试无意义 → 直接终态 DLQ（不占重试次数）。 */
    DATA,

    /** 可能自愈 → 进重试表。 */
    TRANSIENT,

    /** PG 级故障 → 暂停消费，不 ACK，靠 MQ 重投。 */
    FATAL
}
