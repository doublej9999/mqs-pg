package com.mqspg.retry;

import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRow;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.transform.TransformEngine;
import com.mqspg.writer.MergeResult;
import com.mqspg.writer.PgWriter;
import com.mqspg.writer.fold.FoldedRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重试重放：用**任务绑定的配置版本**重新走一遍转换与写入。
 *
 * <p><b>为什么必须用绑定版本</b>：PRD §19 要求历史版本永远不被动态替换。
 * 一条在 v3 接收、写入失败的消息，重试时必须仍按 v3 的映射规则解释 ——
 * 否则「重试」就变成了「用新规则处理旧数据」，结果不可预测。
 * 因此这里取的是 {@code task.configVersion}，而不是当前 ACTIVE 版本。
 *
 * <p>重放不需要折叠（单条消息），也不需要 ACK（消息早已确认），
 * 所以构造的 {@link FoldedRecord} 来源列表为空。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryReplayer {

    private final ConfigRegistry registry;
    private final TransformEngine transformEngine;
    private final PgWriter writer;

    public ReplayOutcome replay(RtRetryTask task) {
        RouteConfig cfg;
        try {
            cfg = registry.get(task.getRouteId(), task.getConfigVersion());
        } catch (RuntimeException e) {
            log.error("加载绑定配置失败: route={} version={}", task.getRouteId(), task.getConfigVersion(), e);
            return ReplayOutcome.configMissing("加载配置异常: " + e.getMessage());
        }
        if (cfg == null) {
            return ReplayOutcome.configMissing(
                    "配置版本不存在: routeId=%d version=%d".formatted(task.getRouteId(), task.getConfigVersion()));
        }

        TargetRow row;
        try {
            row = transformEngine.transform(cfg, task.getPayload());
        } catch (ProcessingException e) {
            return ReplayOutcome.dataError(e.getCode().name(), e.getMessage());
        } catch (RuntimeException e) {
            return ReplayOutcome.dataError("INTERNAL_ERROR", e.getMessage());
        }

        KeyTuple key;
        try {
            key = KeyTuple.ofNormalized(row.keyValues(cfg.upsertKeys()));
        } catch (RuntimeException e) {
            // Upsert Key 为 null 之类：同样是消息内容问题
            return ReplayOutcome.dataError("MISSING_REQUIRED_FIELD", "无法构造 Upsert Key: " + e.getMessage());
        }

        MergeResult merge;
        try {
            merge = writer.write(cfg, List.of(new FoldedRecord(key, row, List.of())));
        } catch (RuntimeException e) {
            return ReplayOutcome.pgFailure(e);
        }

        if (merge.aborted()) {
            return ReplayOutcome.pgFailure(merge.cause());
        }
        if (merge.hasFailure(key)) {
            return ReplayOutcome.rowFailure("PG_CONSTRAINT_VIOLATION", merge.failureOf(key));
        }

        MergeAction action = merge.actionOf(key);
        return ReplayOutcome.success(action == null ? MergeAction.SKIPPED_OLD_VERSION : action);
    }
}
