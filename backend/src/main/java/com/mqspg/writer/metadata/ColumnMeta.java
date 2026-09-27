package com.mqspg.writer.metadata;

/**
 * 目标表的一列。
 *
 * @param name       列名
 * @param pgType     PostgreSQL 类型字面量，直接来自 {@code format_type()}，
 *                   例如 {@code bigint} / {@code numeric(18,2)} / {@code timestamp with time zone}
 * @param nullable   是否可空
 * @param hasDefault 是否有数据库默认值（{@code atthasdef}）
 */
public record ColumnMeta(String name, String pgType, boolean nullable, boolean hasDefault) {

    /**
     * 该列是否必须由配置提供值。
     *
     * <p>PRD §16：目标列为 {@code NOT NULL} 且**无数据库默认值**时，
     * 配置里必须有 mapping / constant / defaultValue 之一，否则发布失败。
     * 有 DB 默认值的列可以不提供 —— 不提供时由数据库补默认值。
     */
    public boolean requiresValue() {
        return !nullable && !hasDefault;
    }
}
