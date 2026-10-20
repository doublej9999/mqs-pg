package com.mqspg.config.dto;

/**
 * 数据源新建 / 修改请求。
 *
 * <p>{@code password} 为**明文**，由服务端经 {@code PasswordCodec} 编码后落库。
 * 修改时留空表示「保留原密码」—— 页面不回显密码，所以留空必须是合法输入。
 *
 * <p>用普通 Java 类型而非 Jackson 2 的 {@code JsonNode}：Web 层是 Jackson 3。
 *
 * <p>{@code poolConfig} 保持 {@code Object}：Web 层是 Jackson 3，若直接声明成
 * {@link PoolConfig}，未知键会在**反序列化阶段**就失败，落到
 * {@code HttpMessageNotReadableException} 的通用分支上，提示只剩一句
 * 「请求体不是合法 JSON」—— 对「键名拼错」这种错误毫无帮助。
 * 由服务层显式解析，才能给出「未知键 xxx，可选 …」这样的提示。
 * 解析与校验见 {@link PoolConfig#parse(Object)}。
 *
 * <p>清空语义：{@code poolConfig} 传 {@code null} = 不改；传 {@code {}} = 清空回默认。
 * 后者是原来做不到的 —— 那时 {@code null} 被 {@code updateById} 跳过，
 * 而空对象又被当成「没有配置」。
 */
public record DatasourceRequest(
        String name,
        String jdbcUrl,
        String username,
        String password,
        Object poolConfig) {
}
