package com.mqspg.config.dto;

import java.util.List;

/** 目标表定义。 */
public record TargetDto(
        Long id,
        String name,
        Long datasourceId,
        String datasourceName,
        String schemaName,
        String tableName,
        List<String> upsertKeys,
        String updateTimeField) {
}
