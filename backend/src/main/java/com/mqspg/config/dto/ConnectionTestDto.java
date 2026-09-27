package com.mqspg.config.dto;

/** 数据源试连结果。 */
public record ConnectionTestDto(
        boolean ok,
        String message,
        String databaseProduct,
        String databaseVersion) {

    public static ConnectionTestDto success(String product, String version) {
        return new ConnectionTestDto(true, "连接成功", product, version);
    }

    public static ConnectionTestDto failure(String message) {
        return new ConnectionTestDto(false, message, null, null);
    }
}
