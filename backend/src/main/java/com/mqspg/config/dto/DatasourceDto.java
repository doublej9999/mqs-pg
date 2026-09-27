package com.mqspg.config.dto;

import java.time.OffsetDateTime;

/**
 * 数据源列表项。
 *
 * <p><b>刻意没有密码字段</b>：明文与密文都不出网。页面需要「是否已设置密码」
 * 这个布尔值来判断编辑框要不要提示重填。
 *
 * @param targetCount 引用该数据源的目标表数量，用于删除前的引用检查提示
 */
public record DatasourceDto(
        Long id,
        String name,
        String jdbcUrl,
        String username,
        boolean hasPassword,
        Object poolConfig,
        long targetCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
