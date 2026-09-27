package com.mqspg.writer.metadata;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.TargetRef;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取并缓存目标表结构。
 *
 * <p>列类型有两个用途，缺一不可：
 * <ol>
 *   <li>MERGE 源 {@code VALUES} 中的裸参数 PostgreSQL 无法推断类型，必须显式 cast；</li>
 *   <li>写入前据可空性做校验，把「必填列缺失」从 PG 约束错误提前为配置/数据错误。</li>
 * </ol>
 *
 * <p>元数据在运行期被视为基本不变，故长期缓存；DDL 变更后需调用
 * {@link #invalidate(TargetRef)}（Console 提供入口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetMetadataReader {

    private static final String COLUMNS_SQL = """
            SELECT a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   a.atthasdef AS has_default
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

    private static final String INDEXES_SQL = """
            SELECT i.indexrelid::regclass::text AS index_name,
                   (SELECT array_agg(a.attname ORDER BY a.attnum)
                      FROM pg_attribute a
                     WHERE a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)) AS cols
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
              AND (i.indisprimary OR i.indisunique)
            """;

    private final TargetDataSourceRegistry dataSources;
    private final Map<TargetRef, TargetTableMeta> cache = new ConcurrentHashMap<>();

    public TargetTableMeta get(TargetRef ref) {
        TargetTableMeta cached = cache.get(ref);
        if (cached != null) {
            return cached;
        }
        TargetTableMeta loaded = load(ref);
        cache.put(ref, loaded);
        return loaded;
    }

    public void invalidate(TargetRef ref) {
        cache.remove(ref);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private TargetTableMeta load(TargetRef ref) {
        JdbcTemplate jdbc = dataSources.jdbc(ref.datasourceId());

        List<ColumnMeta> columns = jdbc.query(COLUMNS_SQL,
                (rs, i) -> new ColumnMeta(
                        rs.getString("column_name"),
                        rs.getString("data_type"),
                        rs.getBoolean("nullable"),
                        rs.getBoolean("has_default")),
                ref.schema(), ref.table());

        if (columns.isEmpty()) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    "目标表不存在或没有可见列: " + ref.qualifiedTable());
        }

        Map<String, ColumnMeta> byName = new LinkedHashMap<>();
        for (ColumnMeta c : columns) {
            byName.put(c.name(), c);
        }

        List<List<String>> uniqueIndexes = jdbc.query(INDEXES_SQL, (rs, i) -> {
            Array arr = rs.getArray("cols");
            if (arr == null) {
                return List.<String>of();
            }
            Object raw = arr.getArray();
            List<String> out = new ArrayList<>();
            if (raw instanceof Object[] objs) {
                for (Object o : objs) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }, ref.schema(), ref.table());

        TargetTableMeta meta = new TargetTableMeta(ref.schema(), ref.table(), byName, uniqueIndexes);
        log.info("已加载目标表元数据: {} 列数={} 唯一索引={}",
                ref.qualifiedTable(), byName.size(), uniqueIndexes);
        return meta;
    }
}
