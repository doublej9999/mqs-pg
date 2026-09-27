package com.mqspg.writer.metadata;

/**
 * 目标表的一列。
 *
 * @param name     列名
 * @param pgType   PostgreSQL 类型字面量，直接来自 {@code format_type()}，
 *                 例如 {@code bigint} / {@code numeric(18,2)} / {@code timestamp with time zone}
 * @param nullable 是否可空
 */
public record ColumnMeta(String name, String pgType, boolean nullable) {
}
