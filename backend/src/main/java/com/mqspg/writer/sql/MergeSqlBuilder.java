package com.mqspg.writer.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.mqspg.common.model.TargetRef;
import com.mqspg.common.model.TargetRow;
import com.mqspg.common.sql.Identifiers;
import com.mqspg.writer.fold.FoldedRecord;
import com.mqspg.writer.metadata.TargetTableMeta;

/**
 * 生成 {@code MERGE ... RETURNING merge_action()}（ADR-04）。
 *
 * <p><b>前置条件</b>：传入的 {@code chunk} 已经过 {@link com.mqspg.writer.fold.BatchFolder}
 * 折叠，每个 Upsert Key 只出现一次。这是 PG 的硬性要求 —— 源中同一目标行出现多次会导致
 * 唯一约束冲突或基数违规。
 *
 * <p>需要 PostgreSQL 17+ 才支持 MERGE 的 RETURNING。
 */
@Component
public class MergeSqlBuilder {

    /**
     * SQL 文本缓存（J3）。
     *
     * <p>MERGE 文本由「表 + 列 + Upsert Key + 守卫字段 + 行数」唯一决定，
     * 而行数通常只有 {@code write-chunk-size}（200）与尾块两三种 ——
     * 也就是说同一个文本被拼了成千上万遍，PG 侧还要为每个新文本重新解析规划
     * （pgjdbc 的 PreparedStatement 缓存按 SQL 文本命中）。这是写入热路径上
     * 最大的一处无效开销。
     *
     * <p>key 用 record 而不是拼接字符串：影响文本的要素少一个就会产生**错误的 SQL**，
     * 而错误 SQL 的症状（列不存在、类型不匹配）离根因很远。用 record 保证
     * 少加一个字段时是编译期/equals 层面的显式行为，而不是字符串里悄悄少拼一段。
     *
     * <p>容量有上限：表结构变更（加列、改类型）会产生新 key，无上限就是内存泄漏。
     * 满了直接清空而不是做 LRU —— 命中率才是目的，而「几种行数 × 几张表」
     * 远小于上限，清空的代价可以忽略，换来的是不必为线程安全写更复杂的结构。
     */
    private final Map<SqlKey, String> sqlCache = new ConcurrentHashMap<>();

    /** 缓存条目上限，超过即整体清空。 */
    private static final int MAX_CACHE_SIZE = 256;

    /** 缓存 key：**全部**影响 SQL 文本的要素。 */
    record SqlKey(String qualifiedTable,
                  List<String> columns,
                  List<String> upsertKeys,
                  String updateTimeField,
                  int rowCount,
                  String columnTypes) {
    }

    /** 当前缓存的 SQL 文本条数，供 gauge {@code mqspg.write.sql.cache.size} 使用。 */
    public int cacheSize() {
        return sqlCache.size();
    }

    public CompiledMerge build(TargetRef target,
                               TargetTableMeta meta,
                               List<String> upsertKeys,
                               String updateTimeField,
                               List<FoldedRecord> chunk) {

        if (chunk.isEmpty()) {
            throw new IllegalArgumentException("chunk 不能为空");
        }

        // 防守式标识符校验。
        //
        // schema / table 会被**直接拼进下面的 SQL 文本**（`MERGE INTO %s.%s`），
        // 不是参数绑定。写入侧（createTarget/updateTarget）已经挡了一道，这里再挡一道：
        // 存量数据可能是校验加上之前就落库的脏值，而这条 SQL 一旦被污染，
        // 影响的是整个写入链路，不是一次请求。
        //
        // 用 IllegalArgumentException 而不是 ProcessingException：走到这里说明
        // 配置已经不该存在了，属于编程/数据契约错误，不该被当成「消息处理失败」重试。
        if (!Identifiers.isValid(target.schema()) || !Identifiers.isValid(target.table())) {
            throw new IllegalArgumentException(
                    "目标表标识符非法（只允许字母、数字、下划线、$）: " + target.qualifiedTable());
        }

        TargetRow sample = chunk.get(0).winnerRow();
        // 列清单只在块首取一次（J3 附带项）：之前每行都调 asMap() 拷一份列名集合
        List<String> columns = new ArrayList<>(sample.asMap().keySet());
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("映射结果没有任何列: " + target.qualifiedTable());
        }

        List<String> updatable = columns.stream().filter(c -> !upsertKeys.contains(c)).toList();
        if (updatable.isEmpty()) {
            throw new IllegalArgumentException(
                    "目标表 %s 的映射列全部是 Upsert Key，MERGE 没有可更新列".formatted(target.qualifiedTable()));
        }
        if (!columns.contains(updateTimeField)) {
            throw new IllegalArgumentException(
                    "映射结果缺少单调守卫字段 %s，无法保证乱序保护".formatted(updateTimeField));
        }

        // 每列的 PG 类型也是 SQL 文本的一部分（`?::numeric`），必须进 key。
        StringBuilder types = new StringBuilder(columns.size() * 8);
        for (String col : columns) {
            types.append(meta.pgType(col)).append(',');
        }
        SqlKey key = new SqlKey(target.qualifiedTable(), columns, List.copyOf(upsertKeys),
                updateTimeField, chunk.size(), types.toString());

        String sql = sqlCache.get(key);
        if (sql == null) {
            // 守卫与列名都在拼文本，不是参数：参数只承载数据值
            String built = render(target, meta, columns, updatable, upsertKeys, updateTimeField, chunk.size());
            if (sqlCache.size() >= MAX_CACHE_SIZE) {
                sqlCache.clear();
            }
            String prev = sqlCache.putIfAbsent(key, built);
            sql = prev == null ? built : prev;
        }

        List<Object> params = new ArrayList<>(chunk.size() * columns.size());
        for (FoldedRecord record : chunk) {
            TargetRow row = record.winnerRow();
            for (String col : columns) {
                params.add(row.get(col));
            }
        }
        return new CompiledMerge(sql, params, upsertKeys.size());
    }

    /** 纯文本渲染：结果只取决于入参与行数，因此可以安全缓存。 */
    private static String render(TargetRef target,
                                 TargetTableMeta meta,
                                 List<String> columns,
                                 List<String> updatable,
                                 List<String> upsertKeys,
                                 String updateTimeField,
                                 int rowCount) {
        StringBuilder sb = new StringBuilder(256 + rowCount * columns.size() * 12);

        // ---- USING (VALUES ...) ----
        sb.append("MERGE INTO ").append(target.qualifiedTable()).append(" AS tgt\nUSING (VALUES ");
        for (int r = 0; r < rowCount; r++) {
            if (r > 0) {
                sb.append(", ");
            }
            sb.append('(');
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    sb.append(", ");
                }
                // 显式 cast 不可省略：VALUES 里的裸参数 PostgreSQL 无法推断类型，
                // 会直接报 "could not determine data type of parameter"
                sb.append("?::").append(meta.pgType(columns.get(c)));
            }
            sb.append(')');
        }
        sb.append(") AS src (").append(String.join(", ", columns)).append(")\n");

        // ---- ON ----
        sb.append("ON ");
        for (int i = 0; i < upsertKeys.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            String k = upsertKeys.get(i);
            sb.append("tgt.").append(k).append(" = src.").append(k);
        }
        sb.append('\n');

        // ---- WHEN MATCHED ----
        //
        // ⚠ 这里**只允许**存在带单调守卫的 WHEN MATCHED。
        // 绝不能照抄 PostgreSQL 官方文档里「带守卫 + 不带守卫」两子句的示例 ——
        // 第二个无守卫子句会兜住所有不满足守卫的候选行，让旧版本数据覆盖新数据，
        // 单调守卫直接失效。
        //
        // 没有任何子句命中时，PG 对该候选行不执行动作，也不出现在 RETURNING 中。
        // 这正是 SKIPPED_OLD_VERSION 的官方依据，也是它唯一的来源。
        sb.append("WHEN MATCHED AND src.").append(updateTimeField)
                .append(" > tgt.").append(updateTimeField).append(" THEN\n  UPDATE SET ");
        for (int i = 0; i < updatable.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String c = updatable.get(i);
            sb.append(c).append(" = src.").append(c);
        }
        sb.append('\n');

        // ---- WHEN NOT MATCHED ----
        sb.append("WHEN NOT MATCHED THEN\n  INSERT (").append(String.join(", ", columns))
                .append(")\n  VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("src.").append(columns.get(i));
        }
        sb.append(")\n");

        // ---- RETURNING ----
        sb.append("RETURNING ");
        for (int i = 0; i < upsertKeys.size(); i++) {
            sb.append("tgt.").append(upsertKeys.get(i)).append(" AS __key").append(i).append(", ");
        }
        sb.append("merge_action() AS __action");

        return sb.toString();
    }
}
