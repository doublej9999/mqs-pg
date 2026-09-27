package com.mqspg.mqs.spi;

import java.time.Instant;

/**
 * 单条消息视图。
 *
 * <p><b>约束</b>：不得暴露具体 MQ 的协议细节（变体 / 分区 / offset / 位点），
 * 否则本 SPI 会被某个具体 MQ 绑死，违背 ADR-01 的初衷。
 */
public interface MqsMessage {

    /**
     * 全局唯一消息 id。
     *
     * <p><b>必须是稳定的</b>：同一条消息多次投递必须返回相同值。
     * 重试表的幂等去重依赖此性质（tech-design §10.2）。
     */
    String messageId();

    String topic();

    String tag();

    byte[] body();

    /** 消息产生时间。 */
    Instant bornTime();

    /** 已投递次数，从 1 开始。V1 的重试由应用侧控制，此值仅用于可观测性。 */
    int deliveryAttempt();

    /** 平台确认句柄，对上层不透明。 */
    String receiptHandle();
}
