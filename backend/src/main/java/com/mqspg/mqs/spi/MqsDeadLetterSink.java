package com.mqspg.mqs.spi;

/**
 * 终态失败投递（可选能力）。
 *
 * <p>V1 的 DLQ 落在数据库（{@code rt_error_record.is_final = true}），
 * 本接口保留给「平台 MQS 自带死信队列」的场景。
 */
public interface MqsDeadLetterSink {

    void send(MqsMessage original, FailureMeta meta);
}
