package com.mqspg.writer;

import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import com.mqspg.writer.fold.FoldedRecord;
import com.mqspg.writer.metadata.TargetMetadataReader;
import com.mqspg.writer.metadata.TargetTableMeta;
import com.mqspg.writer.sql.CompiledMerge;
import com.mqspg.writer.sql.MergeSqlBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 批量写入器（tech-design §9）。
 *
 * <p>职责：
 * <ol>
 *   <li>把折叠后的记录按 {@code write-chunk-size} 切块，逐块执行单条 MERGE；</li>
 *   <li>把 RETURNING 的逐行结果归属回 Upsert Key；</li>
 *   <li>推导 {@code SKIPPED_OLD_VERSION}（PRD §7 的 {@code <=} 分支）；</li>
 *   <li>语句级失败时做**二分隔离**，把数据级错误收敛到具体行（tech-design §9.3）。</li>
 * </ol>
 *
 * <p><b>错误分类决定行为，这是本类的核心</b>：
 * <ul>
 *   <li>数据级（SQLSTATE 21/22/23）→ 二分隔离到具体行，该行进重试表，其余行照常 ACK；</li>
 *   <li>PG 级（连接失败、资源不足、运维干预、序列化失败、死锁）→ 中止整批，
 *       **既不 ACK 也不写重试表**，让 MQ 重新投递（PRD §24 / §50）。</li>
 * </ul>
 * 无法判定 SQLSTATE 时按 PG 级处理：宁可暂停，也不能把故障行误判成数据错误而 ACK 掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgWriter {

    private final TargetDataSourceRegistry dataSources;
    private final TargetMetadataReader metadataReader;
    private final MergeSqlBuilder sqlBuilder;

    @Value("${mqs-pg.batch.write-chunk-size:200}")
    private int writeChunkSize = 200;

    public MergeResult write(RouteConfig cfg, List<FoldedRecord> folded) {
        Map<KeyTuple, MergeAction> actions = new LinkedHashMap<>();
        Map<KeyTuple, String> failures = new LinkedHashMap<>();
        if (folded.isEmpty()) {
            return MergeResult.ok(actions, failures);
        }

        TargetTableMeta meta = metadataReader.get(cfg.target());
        int total = folded.size();

        for (int from = 0; from < total; from += writeChunkSize) {
            List<FoldedRecord> chunk = folded.subList(from, Math.min(from + writeChunkSize, total));
            try {
                executeChunk(cfg, meta, chunk, actions);
            } catch (DataAccessException e) {
                if (!isDataLevel(e)) {
                    log.error("PG 级写入故障，中止本批次: target={} sqlState={}",
                            cfg.target().qualifiedTable(), sqlState(e), e);
                    return MergeResult.aborted(e, actions, failures);
                }
                log.warn("语句级写入失败，进入二分隔离: target={} 行数={} sqlState={} reason={}",
                        cfg.target().qualifiedTable(), chunk.size(), sqlState(e), rootMessage(e));
                try {
                    isolate(cfg, meta, chunk, actions, failures, e);
                } catch (DataAccessException e2) {
                    log.error("隔离过程中出现 PG 级故障，中止本批次: sqlState={}", sqlState(e2), e2);
                    return MergeResult.aborted(e2, actions, failures);
                }
            }
        }

        // 未出现在 RETURNING 中的键 = 被单调守卫跳过的旧版本。
        // 依赖 PG 的官方语义：没有任何子句命中时该候选行不产生动作。
        for (FoldedRecord f : folded) {
            if (!actions.containsKey(f.key()) && !failures.containsKey(f.key())) {
                actions.put(f.key(), MergeAction.SKIPPED_OLD_VERSION);
            }
        }
        return MergeResult.ok(actions, failures);
    }

    // ------------------------------------------------------------------

    private void executeChunk(RouteConfig cfg, TargetTableMeta meta, List<FoldedRecord> chunk,
                              Map<KeyTuple, MergeAction> actions) {
        JdbcTemplate jdbc = dataSources.jdbc(cfg.target().datasourceId());
        CompiledMerge compiled = sqlBuilder.build(
                cfg.target(), meta, cfg.upsertKeys(), cfg.updateTimeField(), chunk);

        Map<KeyTuple, MergeAction> got = jdbc.query(compiled.sql(), rs -> {
            Map<KeyTuple, MergeAction> out = new LinkedHashMap<>();
            while (rs.next()) {
                List<Object> kv = new ArrayList<>(compiled.keyCount());
                for (int i = 0; i < compiled.keyCount(); i++) {
                    kv.add(rs.getObject("__key" + i));
                }
                // 规范化：JDBC 返回的类型可能与输入不同（输入 Integer、返回 Long）
                out.put(KeyTuple.ofNormalized(kv), MergeAction.fromPgAction(rs.getString("__action")));
            }
            return out;
        }, compiled.params().toArray());

        if (got != null && !got.isEmpty()) {
            actions.putAll(got);
        }
    }

    /**
     * 二分隔离：把一条失败语句反复对半拆，直到定位到单行。
     *
     * <p>复杂度 {@code O(k·log n)}（k 为坏行数）。相比「整批进重试表」，
     * 它能保证坏行不阻塞同批次的好行（PRD §49）。
     */
    private void isolate(RouteConfig cfg, TargetTableMeta meta, List<FoldedRecord> chunk,
                         Map<KeyTuple, MergeAction> actions, Map<KeyTuple, String> failures,
                         DataAccessException cause) {
        if (chunk.size() == 1) {
            FoldedRecord f = chunk.get(0);
            failures.put(f.key(), "SQLSTATE %s: %s".formatted(sqlState(cause), rootMessage(cause)));
            log.warn("行级写入失败（已隔离，将进入重试）: target={} key={} 关联消息数={} reason={}",
                    cfg.target().qualifiedTable(), f.key(), f.sources().size(), rootMessage(cause));
            return;
        }
        int mid = chunk.size() / 2;
        List<List<FoldedRecord>> halves = List.of(chunk.subList(0, mid), chunk.subList(mid, chunk.size()));
        for (List<FoldedRecord> half : halves) {
            try {
                executeChunk(cfg, meta, half, actions);
            } catch (DataAccessException e) {
                if (!isDataLevel(e)) {
                    throw e; // 交给上层判定为 PG 级故障
                }
                isolate(cfg, meta, half, actions, failures, e);
            }
        }
    }

    /** 数据级错误：可以用二分隔离定位到具体行。 */
    static boolean isDataLevel(DataAccessException e) {
        String state = sqlState(e);
        if (state == null) {
            return false;
        }
        return state.startsWith("21")   // cardinality_violation
                || state.startsWith("22") // data_exception（数值越界、非法文本表示……）
                || state.startsWith("23");// integrity_constraint_violation
    }

    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se && se.getSQLState() != null) {
                return se.getSQLState();
            }
        }
        return null;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = (e instanceof DataAccessException dae) ? dae.getMostSpecificCause() : e;
        if (root == null) {
            root = e;
        }
        return root.getMessage();
    }
}
