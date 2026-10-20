package com.mqspg.retry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.KeyTuple;
import com.mqspg.common.model.MergeAction;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRow;
import com.mqspg.config.entity.CfgVersion;
import com.mqspg.config.mapper.CfgVersionMapper;
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
 *
 * <p><b>「取不到配置」有两种</b>，必须分开：
 * <ul>
 *   <li>数据库抖动、装配时抛异常 → {@code CONFIG_MISSING}，下一轮再试；</li>
 *   <li>库里确实没有这个版本 → {@code CONFIG_GONE}，由调度器缓冲有限次后进 DLQ。</li>
 * </ul>
 * 不分开的后果是本轮要修的缺陷：绑定的版本被删后，任务每轮都「暂时失败」，
 * {@code next_retry_at} 只是往后推，永远不消耗次数、永远进不了 DLQ，表只增不减。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryReplayer {

    private final ConfigRegistry registry;
    private final TransformEngine transformEngine;
    private final PgWriter writer;
    private final CfgVersionMapper versionMapper;

    /** 供测试与诊断：一次重放最多探测几次「版本是否真的没了」。 */
    public ReplayOutcome replay(RtRetryTask task) {
        RouteConfig cfg;
        try {
            cfg = registry.get(task.getRouteId(), task.getConfigVersion());
        } catch (RuntimeException e) {
            // 读配置本身抛异常：可能是 DB 抖动，也可能是路由/目标表被删。
            // 这里**不**急着判死：交给下面的二次确认逻辑判断到底是不是「真的没了」。
            log.warn("加载绑定配置抛异常: route={} version={} 原因={}",
                    task.getRouteId(), task.getConfigVersion(), e.getMessage());
            return probeMissing(task) ? configGoneOf(task)
                    : ReplayOutcome.configMissing("加载配置异常: " + e.getMessage());
        }
        if (cfg == null) {
            return probeMissing(task) ? configGoneOf(task)
                    : ReplayOutcome.configMissing(
                            "配置版本暂不可用: routeId=%d version=%d"
                                    .formatted(task.getRouteId(), task.getConfigVersion()));
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

    /**
     * 直接查一次库，确认绑定的版本是不是**真的**不存在了。
     *
     * <p>注册表返回 null 有两种来源：版本确实被删（永久），或缓存/装配路径上的
     * 临时问题（可恢复）。多花一次单行查询把它区分开，代价远小于「把一条能成功的
     * 消息推进 DLQ」或「一条死任务永远留在表里」。
     *
     * <p>查询本身抛异常时一律当「不确定」处理（返回 false → 走 CONFIG_MISSING），
     * 宁可多等几轮，也不在数据库抖动时误杀。
     */
    private boolean probeMissing(RtRetryTask task) {
        try {
            Long count = versionMapper.selectCount(Wrappers.<CfgVersion>lambdaQuery()
                    .eq(CfgVersion::getRouteId, task.getRouteId())
                    .eq(CfgVersion::getVersion, task.getConfigVersion()));
            return count == null || count == 0;
        } catch (RuntimeException e) {
            log.warn("确认配置版本是否存在时查询失败（按临时问题处理）: route={} version={} 原因={}",
                    task.getRouteId(), task.getConfigVersion(), e.getMessage());
            return false;
        }
    }

    private static ReplayOutcome configGoneOf(RtRetryTask task) {
        return ReplayOutcome.configGone(
                "绑定的配置版本已不存在: routeId=%d version=%d"
                        .formatted(task.getRouteId(), task.getConfigVersion()));
    }
}
