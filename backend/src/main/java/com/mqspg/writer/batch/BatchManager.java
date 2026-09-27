package com.mqspg.writer.batch;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.MergeAction;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRow;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.consumer.PgHealthGate;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.retry.RetryService;
import com.mqspg.transform.TransformEngine;
import com.mqspg.writer.MergeResult;
import com.mqspg.writer.PgWriter;
import com.mqspg.writer.fold.BatchFolder;
import com.mqspg.writer.fold.CandidateRecord;
import com.mqspg.writer.fold.FoldedRecord;
import com.mqspg.writer.fold.MessageRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次处理器（tech-design §7）：把「一批消息」变成「逐条处置结论」。
 *
 * <p>本类不直接调用 ACK —— 它只产出 {@link MessageDisposition}，由消费循环统一执行。
 * 这样「是否该 ACK」的判定集中在一处，可被单元测试完整覆盖。
 *
 * <p><b>ACK 不变量</b>：
 * <pre>
 * ack(message) ⟹ (PG 已提交) ∨ (∃ 持久化的重试任务/错误记录)
 * </pre>
 * 因此所有 {@code ACK_DEFERRED} / {@code ACK_DLQ} 结论都**只在落库成功之后**才产出；
 * 落库失败一律降级为 {@code NO_ACK}（宁可让 MQ 重投，也不能丢消息）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchManager {

    private final ConfigRegistry registry;
    private final TransformEngine transformEngine;
    private final BatchFolder folder;
    private final PgWriter writer;
    private final RetryService retryService;
    private final PgHealthGate healthGate;

    /**
     * @param routeId 路由
     * @param batch   已按接收顺序排列、且**已绑定配置版本**的消息
     */
    public BatchResult process(long routeId, List<BufferedMessage> batch) {
        if (batch.isEmpty()) {
            return BatchResult.ok(List.of());
        }

        // 按绑定版本分组：配置发布切换的瞬间，同一批次里可能混有两个版本的消息。
        // 每条消息必须用它自己接收时刻的版本处理（PRD §18/§19）。
        Map<Integer, List<BufferedMessage>> byVersion = new LinkedHashMap<>();
        for (BufferedMessage m : batch) {
            byVersion.computeIfAbsent(m.configVersion(), k -> new ArrayList<>()).add(m);
        }

        List<MessageDisposition> dispositions = new ArrayList<>(batch.size());

        for (Map.Entry<Integer, List<BufferedMessage>> entry : byVersion.entrySet()) {
            int version = entry.getKey();
            List<BufferedMessage> group = entry.getValue();

            RouteConfig cfg;
            try {
                cfg = registry.get(routeId, version);
            } catch (RuntimeException e) {
                cfg = null;
                log.error("加载配置失败: route={} version={}", routeId, version, e);
            }

            if (cfg == null) {
                // 配置缺失属于系统级问题：不 ACK，暂停消费，等人工修复
                String reason = "配置版本不存在: routeId=%d version=%d".formatted(routeId, version);
                log.error("{}，本组 {} 条消息均不 ACK", reason, group.size());
                for (BufferedMessage m : group) {
                    dispositions.add(MessageDisposition.noAck(m.handle(), reason));
                }
                return BatchResult.aborted(reason, dispositions);
            }

            boolean aborted = processVersionGroup(routeId, cfg, group, dispositions);
            if (aborted) {
                return BatchResult.aborted("PG 级故障，批次已中止", dispositions);
            }
        }

        return BatchResult.ok(dispositions);
    }

    // ------------------------------------------------------------------

    /** @return true 表示遇到 PG 级故障，需中止后续处理 */
    private boolean processVersionGroup(long routeId, RouteConfig cfg, List<BufferedMessage> group,
                                        List<MessageDisposition> dispositions) {
        List<CandidateRecord> candidates = new ArrayList<>(group.size());

        for (BufferedMessage m : group) {
            try {
                TargetRow row = transformEngine.transform(cfg, m.handle().body());
                candidates.add(new CandidateRecord(m, row));
            } catch (ProcessingException e) {
                handleTransformFailure(routeId, cfg, m, e, dispositions);
            } catch (RuntimeException e) {
                handleTransformFailure(routeId, cfg, m,
                        new ProcessingException(ErrorCode.INTERNAL_ERROR, e.getMessage(), e), dispositions);
            }
        }

        if (candidates.isEmpty()) {
            return false;
        }

        List<FoldedRecord> folded = folder.fold(candidates, cfg.upsertKeys(), cfg.updateTimeField());
        MergeResult merge = writer.write(cfg, folded);

        Long datasourceId = cfg.target().datasourceId();
        if (merge.aborted()) {
            healthGate.recordFailure(datasourceId, merge.cause());
            // 中止：这一组里**所有**消息都不 ACK，让 MQ 重新投递
            for (FoldedRecord f : folded) {
                for (MessageRef ref : f.sources()) {
                    dispositions.add(MessageDisposition.noAck(ref.handle(),
                            "PG 级故障，未写入: " + abbreviate(merge.cause())));
                }
            }
            return true;
        }
        healthGate.recordSuccess(datasourceId);

        for (FoldedRecord f : folded) {
            if (merge.hasFailure(f.key())) {
                handleRowFailure(routeId, cfg, f, merge.failureOf(f.key()), dispositions);
                continue;
            }
            MergeAction action = merge.actionOf(f.key());
            if (action == null) {
                // 理论上不可达：PgWriter 会为未命中的键补 SKIPPED_OLD_VERSION
                for (MessageRef ref : f.sources()) {
                    dispositions.add(MessageDisposition.noAck(ref.handle(), "写入结果缺失"));
                }
                continue;
            }
            for (MessageRef ref : f.sources()) {
                dispositions.add(MessageDisposition.ack(ref.handle(), action.name()));
            }
        }

        log.debug("批次写入完成: route={} version={} 候选={} 折叠后={} 动作分布={}",
                routeId, cfg.version(), candidates.size(), folded.size(), summarize(merge));
        return false;
    }

    // ------------------------------------------------------------------

    /**
     * 转换阶段失败。
     *
     * <p>可重试的（TRANSIENT）落重试表后 ACK；
     * 不可重试的（DATA，即消息内容本身有问题）记错误流水后 ACK —— 这就是
     * tech-design 的偏差 D-01：PRD §22 字面要求「不 ACK」，但那会让一条坏消息
     * 永久堵住同一个 Topic 的后续消息，与 PRD §49「无效记录不得阻塞有效记录」冲突。
     * 取舍是：坏消息落库留痕并 ACK，人工可查可重放。
     */
    private void handleTransformFailure(long routeId, RouteConfig cfg, BufferedMessage m,
                                        ProcessingException e, List<MessageDisposition> out) {
        MqsMessage handle = m.handle();

        if (retryService.shouldRetry(e)) {
            persistThenAck(routeId, cfg, m, e, 1, out);
            return;
        }

        try {
            retryService.recordError(handle, routeId, cfg.version(),
                    e.getCode().name(), e.getStage().name(), e.getMessage(),
                    0, true);
            out.add(MessageDisposition.ackDlq(handle, e.getCode() + ": " + abbreviate(e)));
        } catch (RuntimeException persistFailure) {
            // 留痕失败 → 不能 ACK，否则这条坏消息会彻底消失
            log.error("错误流水落库失败，不 ACK: route={} message={}", routeId, m.handle().messageId(), persistFailure);
            out.add(MessageDisposition.noAck(handle,
                    "错误流水落库失败: " + persistFailure.getMessage()));
        }
    }

    /** PG 行级失败：把该行关联的**所有**消息登记重试。 */
    private void handleRowFailure(long routeId, RouteConfig cfg, FoldedRecord f, String reason,
                                  List<MessageDisposition> out) {
        for (MessageRef ref : f.sources()) {
            MqsMessage handle = ref.handle();
            try {
                retryService.scheduleDeferred(handle, routeId, ref.configVersion(), null, 1,
                        new ProcessingException(ErrorCode.PG_CONSTRAINT_VIOLATION, reason));
                out.add(MessageDisposition.ackDeferred(handle, "已落重试表: " + abbreviate(reason)));
            } catch (RuntimeException e) {
                log.error("登记重试任务失败，不 ACK: route={} message={}", routeId, ref.messageId(), e);
                out.add(MessageDisposition.noAck(handle, "重试任务登记失败: " + e.getMessage()));
            }
        }
    }

    /** 重试分支的公共路径：先落库，成功后才 ACK。 */
    private void persistThenAck(long routeId, RouteConfig cfg, BufferedMessage m,
                                ProcessingException e, int attempt, List<MessageDisposition> out) {
        MqsMessage handle = m.handle();
        try {
            retryService.scheduleDeferred(handle, routeId, cfg.version(), null, attempt, e);
            out.add(MessageDisposition.ackDeferred(handle, "已落重试表: " + e.getCode()));
        } catch (RuntimeException persistFailure) {
            log.error("登记重试任务失败，不 ACK: route={} message={}", routeId, m.handle().messageId(), persistFailure);
            out.add(MessageDisposition.noAck(handle, "重试任务登记失败: " + persistFailure.getMessage()));
        }
    }

    private static String summarize(MergeResult merge) {
        Map<MergeAction, Integer> counts = new LinkedHashMap<>();
        for (MergeAction a : merge.actions().values()) {
            counts.merge(a, 1, Integer::sum);
        }
        return counts + (merge.failures().isEmpty() ? "" : " 失败=" + merge.failures().size());
    }

    private static String abbreviate(Throwable t) {
        if (t == null) {
            return "(未知)";
        }
        String s = t.getMessage();
        if (s == null) {
            return t.getClass().getSimpleName();
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "(未知)";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
