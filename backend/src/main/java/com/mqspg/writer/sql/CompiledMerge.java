package com.mqspg.writer.sql;

import java.util.List;

/**
 * 已编译的 MERGE 语句。
 *
 * @param sql      带 {@code ?} 占位符与显式 cast 的 SQL
 * @param params   与占位符顺序一一对应的参数
 * @param keyCount Upsert Key 的列数（用于解析 RETURNING 结果）
 */
public record CompiledMerge(String sql, List<Object> params, int keyCount) {

    public CompiledMerge {
        params = List.copyOf(params);
    }
}
