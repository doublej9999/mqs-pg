package com.mqspg.config.dto;

/**
 * 目标表的一列，供页面渲染下拉框与映射编辑器。
 *
 * @param name         列名
 * @param pgType       PostgreSQL 类型
 * @param nullable     是否可空
 * @param hasDefault   是否有数据库默认值
 * @param primaryKey   是否属于主键
 * @param unique       是否属于某个唯一索引
 * @param requiresValue 是否必须由配置提供值（NOT NULL 且无默认值，PRD §16）
 * @param suggestedTransform 依列类型推荐的转换类型，页面预填用
 */
public record ColumnDto(
        String name,
        String pgType,
        boolean nullable,
        boolean hasDefault,
        boolean primaryKey,
        boolean unique,
        boolean requiresValue,
        String suggestedTransform) {
}
