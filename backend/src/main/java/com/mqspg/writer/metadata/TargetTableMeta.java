package com.mqspg.writer.metadata;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目标表的运行时元数据。
 *
 * @param schema         schema
 * @param table          表名
 * @param columns        列名 → 列元数据（保持列顺序）
 * @param uniqueIndexes  每个唯一索引/主键的列集合
 */
public record TargetTableMeta(
        String schema,
        String table,
        Map<String, ColumnMeta> columns,
        List<List<String>> uniqueIndexes) {

    public TargetTableMeta {
        columns = Map.copyOf(columns);
        uniqueIndexes = List.copyOf(uniqueIndexes);
    }

    public ColumnMeta column(String name) {
        ColumnMeta c = columns.get(name);
        if (c == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "目标表 %s.%s 不存在列: %s".formatted(schema, table, name));
        }
        return c;
    }

    /** PG 类型字面量，用作 MERGE 源 VALUES 的显式 cast。 */
    public String pgType(String column) {
        return column(column).pgType();
    }

    public boolean hasColumn(String name) {
        return columns.containsKey(name);
    }

    /**
     * 是否存在「列集合恰好等于 keys」的唯一索引或主键。
     *
     * <p>MERGE 的 ON 条件依赖它保证一个目标行最多匹配一个源行。
     * 发布配置时应做此校验（tech-design 附录 C-06）。
     */
    public boolean hasUniqueIndexOn(List<String> keys) {
        Set<String> want = new HashSet<>(keys);
        return uniqueIndexes.stream().anyMatch(idx -> new HashSet<>(idx).equals(want));
    }
}
