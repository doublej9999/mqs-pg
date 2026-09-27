package com.mqspg.writer.fold;

/**
 * 一条原始消息的引用，用于把 PG 写入结果归属回消息以决定 ACK。
 *
 * @param messageId     消息 id（日志用）
 * @param configVersion 绑定版本
 * @param handle        不透明的平台句柄
 */
public record MessageRef(String messageId, int configVersion, Object handle) {
}
