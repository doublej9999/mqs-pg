package com.mqspg.common.model;

import java.util.List;

/**
 * 同步目标表引用（由 {@code cfg_target} + {@code cfg_datasource} 展开）。
 *
 * @param targetId        cfg_target.id
 * @param datasourceId    cfg_datasource.id
 * @param schema          目标 schema
 * @param table           目标表名
 * @param upsertKeys      Upsert Key 列名，默认 {@code ["id"]}
 * @param updateTimeField 单调守卫字段，默认 {@code update_time}
 */
public record TargetRef(
        Long targetId,
        Long datasourceId,
        String schema,
        String table,
        List<String> upsertKeys,
        String updateTimeField) {

    public TargetRef {
        upsertKeys = List.copyOf(upsertKeys);
    }

    /** {@code schema.table}，用于日志与 SQL 生成。 */
    public String qualifiedTable() {
        return schema + "." + table;
    }
}
