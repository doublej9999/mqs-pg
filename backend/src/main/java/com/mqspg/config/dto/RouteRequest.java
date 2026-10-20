package com.mqspg.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 路由新建 / 修改请求。
 *
 * <p>{@code topic} + {@code tag} 决定消费什么，{@code targetId} 决定写到哪张表。
 * 创建时路由为 {@code INACTIVE} 且没有 ACTIVE 版本 —— 必须再配一个版本并激活，
 * 消费者才会真正启动（见 {@code ConsumerManager#ensureStarted}）。
 *
 * <p><b>长度上限照抄 DDL</b>（{@code V1__init_schema.sql}）：name/tag 是 VARCHAR(128)/VARCHAR(256)，
 * topic 是 VARCHAR(256)。原来没有长度校验，超长值直接撞 PG 的
 * SQLSTATE 22001（value too long），用户看到的是数据库原文。
 * 注解先拦下来，提示就能写成「topic 最长 256 个字符」。
 *
 * <p>注意 {@code tag} 在 DDL 里是 {@code NOT NULL DEFAULT ''} —— 允许为空串，
 * 所以只限长度不加 {@code @NotBlank}。
 */
public record RouteRequest(
        @NotBlank(message = "路由名称不能为空")
        @Size(max = 128, message = "路由名称最长 128 个字符")
        String name,

        @NotBlank(message = "Topic 不能为空")
        @Size(max = 256, message = "Topic 最长 256 个字符")
        String topic,

        @Size(max = 256, message = "Tag 最长 256 个字符")
        String tag,

        @NotNull(message = "必须选择目标表")
        Long targetId) {
}
