package com.mqspg.writer.batch;

import java.time.Instant;

import com.mqspg.mqs.spi.MqsMessage;

/**
 * 已进入批次缓冲的消息。
 *
 * @param handle        平台消息句柄（用于 ACK）
 * @param configVersion **接收时刻**绑定的配置版本（PRD §18）
 * @param receivedAt    接收时间
 */
public record BufferedMessage(
        MqsMessage handle,
        int configVersion,
        Instant receivedAt) {
}
