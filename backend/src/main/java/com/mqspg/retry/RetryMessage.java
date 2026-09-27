package com.mqspg.retry;

import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.retry.entity.RtRetryTask;

import java.time.Instant;

/**
 * 从重试任务还原出的消息视图。
 *
 * <p>重试阶段手里没有平台句柄（消息早已 ACK），但任务行里保存了定位与重放所需的
 * 全部字段。把 {@link RtRetryTask} 适配成 {@link MqsMessage} 的好处是：
 * DLQ 上报接口（{@link com.mqspg.mqs.spi.MqsDeadLetterSink}）与日志能在
 * 「首次消费」和「重试」两条路径上看到同一种对象。
 */
public record RetryMessage(
        String messageId,
        String topic,
        String tag,
        byte[] payload,
        Instant bornTime,
        int attempt) implements MqsMessage {

    public static RetryMessage of(RtRetryTask task) {
        return new RetryMessage(
                task.getMessageId(),
                task.getTopic(),
                task.getTag(),
                task.getPayload(),
                task.getCreatedAt() == null ? Instant.now() : task.getCreatedAt().toInstant(),
                task.getAttempt() == null ? 1 : task.getAttempt());
    }

    @Override
    public byte[] body() {
        return payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public int deliveryAttempt() {
        return attempt;
    }

    @Override
    public String receiptHandle() {
        return "retry:" + messageId;
    }
}
