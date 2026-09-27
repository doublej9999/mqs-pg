package com.mqspg.common.model;

import java.util.Map;

/**
 * 单个字段的转换声明（tech-design §8.4 / PRD §13）。
 *
 * @param type         long / integer / decimal / string / boolean / timestamp / enum
 * @param enumMapping  仅 {@code type = enum} 时的取值映射，如 {@code {"CREATED":1}}
 * @param pattern      仅 {@code type = timestamp} 时的时间格式
 * @param zone         仅 {@code type = timestamp} 时的时区
 */
public record TransformSpec(
        String type,
        Map<String, String> enumMapping,
        String pattern,
        String zone) {

    public static TransformSpec of(String type) {
        return new TransformSpec(type == null ? "string" : type.toLowerCase(), Map.of(), null, null);
    }

    public boolean isEnum() {
        return "enum".equalsIgnoreCase(type);
    }

    public boolean isTimestamp() {
        return "timestamp".equalsIgnoreCase(type);
    }
}
