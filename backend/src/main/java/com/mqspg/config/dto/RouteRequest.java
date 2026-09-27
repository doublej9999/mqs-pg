package com.mqspg.config.dto;

/**
 * 路由新建 / 修改请求。
 *
 * <p>{@code topic} + {@code tag} 决定消费什么，{@code targetId} 决定写到哪张表。
 * 创建时路由为 {@code INACTIVE} 且没有 ACTIVE 版本 —— 必须再配一个版本并激活，
 * 消费者才会真正启动（见 {@code ConsumerManager#ensureStarted}）。
 */
public record RouteRequest(
        String name,
        String topic,
        String tag,
        Long targetId) {
}
