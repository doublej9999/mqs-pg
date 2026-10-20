package com.mqspg.writer.metadata;

import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.TargetRef;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * 读取唯一索引/主键的列集合。
     *
     * <p><b>{@code i.indpred IS NULL} 不能漏</b>：那是部分索引（{@code CREATE UNIQUE
     * INDEX ... WHERE deleted = false}）。部分唯一索引**不构成 MERGE 的唯一性保证** ——
     * MERGE 的 ON 条件不带那个谓词，一旦谓词外的行也存在，同一条源行可能匹配多行，
     * PG 报 SQLSTATE 21000 cardinality violation，整批进二分隔离。
     * 这正是发布校验该防的错误，而原来的 SQL 只筛 {@code indisprimary OR indisunique}，
     * 会把部分索引当成普通唯一索引放行。
     *
     * <p>但也不能「看不见」它：用户建了部分唯一索引却不知道为什么发布不通过，
     * 只会更困惑。所以这里把部分索引单独捞出来（{@code indpred IS NOT NULL}），
     * 由 {@link TargetTableMeta} 持有，校验器据此给出**指名道姓**的提示。
     */
    private static final String INDEXES_SQL = """
            SELECT i.indexrelid::regclass::text AS index_name,
                   (i.indpred IS NOT NULL) AS is_partial,
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

        List<List<String>> uniqueIndexes = new ArrayList<>();
        List<List<String>> partialUniqueIndexes = new ArrayList<>();
        jdbc.query(INDEXES_SQL, rs -> {
            Array arr = rs.getArray("cols");
            if (arr == null) {
                return;
            }
            Object raw = arr.getArray();
            List<String> cols = new ArrayList<>();
            if (raw instanceof Object[] objs) {
                for (Object o : objs) {
                    cols.add(String.valueOf(o));
                }
            }
            if (rs.getBoolean("is_partial")) {
                partialUniqueIndexes.add(cols);
            } else {
                uniqueIndexes.add(cols);
            }
        }, ref.schema(), ref.table());

        TargetTableMeta meta = new TargetTableMeta(
                ref.schema(), ref.table(), byName, uniqueIndexes, partialUniqueIndexes);
        log.info("已加载目标表元数据: {} 列数={} 唯一索引={} 部分唯一索引={}",
                ref.qualifiedTable(), byName.size(), uniqueIndexes, partialUniqueIndexes);
        return meta;
    }
}
