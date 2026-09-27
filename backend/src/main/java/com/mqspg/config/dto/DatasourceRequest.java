package com.mqspg.config.dto;

/**
 * 数据源新建 / 修改请求。
 *
 * <p>{@code password} 为**明文**，由服务端经 {@code PasswordCodec} 编码后落库。
 * 修改时留空表示「保留原密码」—— 页面不回显密码，所以留空必须是合法输入。
 *
 * <p>用普通 Java 类型而非 Jackson 2 的 {@code JsonNode}：Web 层是 Jackson 3。
 */
public record DatasourceRequest(
        String name,
        String jdbcUrl,
        String username,
        String password,
        Object poolConfig) {
}
