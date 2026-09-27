package com.mqspg.config.dto;

import java.util.List;

/** 目标表新建 / 修改请求。 */
public record TargetRequest(
        String name,
        Long datasourceId,
        String schemaName,
        String tableName,
        List<String> upsertKeys,
        String updateTimeField) {
}
