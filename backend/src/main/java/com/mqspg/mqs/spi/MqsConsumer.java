package com.mqspg.mqs.spi;

import java.time.Duration;
import java.util.List;

/**
 * 平台 MQS 消费者抽象（ADR-01）。实现必须线程安全。
 *
 * <p>已确认平台 MQS 支持：批量拉取 + 显式 ACK。
 */
public interface MqsConsumer extends AutoCloseable {

    /**
     * 批量拉取。
     *
     * @param maxNum            单次最大条数
     * @param invisibleDuration 拉取后消息的不可见时长；必须大于预期批处理耗时，
     *                          否则会并发重复投递
     * @return 消息列表；无消息时阻塞至多一小段时间后返回空列表
     */
    List<MqsMessage> receive(int maxNum, Duration invisibleDuration);

    /**
     * 确认消息。仅允许对本次 {@link #receive} 返回且未确认的消息调用。
     *
     * <p>ACK 可能失败（网络抖动等）。调用方必须容忍「ACK 失败后消息被重新投递」，
     * 这依赖 PG 的幂等 Upsert 来保证最终状态正确（PRD §28）。
     */
    void ack(MqsMessage message);

    /**
     * 延长不可见时间，用于退避后重新投递（不确认）。
     *
     * <p>V1 的重试走应用侧重试表（ADR-02），此方法保留给「MQ 层退避」的混合模式。
     */
    void changeInvisibleDuration(MqsMessage message, Duration duration);

    @Override
    void close();
}
