package com.mqspg.retry;

import com.mqspg.common.error.ErrorStage;
import com.mqspg.common.logging.MdcScope;
import com.mqspg.mqs.spi.FailureMeta;
import com.mqspg.mqs.spi.MqsDeadLetterSink;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重试调度器（tech-design §10，ADR-02）。
 *
 * <p>每一轮做三件事：
 * <ol>
 *   <li>回收租约过期的 {@code RUNNING} 任务（实例崩溃后任务不会永久卡住）；</li>
 *   <li>用 {@code FOR UPDATE SKIP LOCKED} 抢占到期的 {@code PENDING} 任务 ——
 *       多实例部署时不会重复领取；</li>
 *   <li>逐个重放，按结果决定「成功 / 重新排队 / 暂缓 / 进 DLQ」。</li>
 * </ol>
 *
 * <p><b>为什么是轮询而不是就地重试</b>：就地重试会占住消费线程，
 * 消息的不可见期一过就会被并发消费，同一批次反复重放。把重试拆到独立调度器后，
 * 消费循环只负责一次尝试，重试节奏完全由 {@code next_retry_at} 控制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final RtRetryTaskMapper retryTaskMapper;
    private final RetryProperties props;
    private final RetryReplayer replayer;
    private final RetryService retryService;
    private final MqsDeadLetterSink deadLetterSink;

    @Scheduled(fixedDelayString = "${mqs-pg.retry.poll-interval:1s}")
    public void poll() {
        try {
            int reclaimed = retryTaskMapper.reclaimExpiredLeases();
            if (reclaimed > 0) {
                log.warn("回收了 {} 个租约过期的重试任务", reclaimed);
            }

            long leaseSeconds = leaseSecondsFor(props.getBatchSize());
            List<RtRetryTask> due = retryTaskMapper.claimDue(props.getBatchSize(), leaseSeconds);
            if (due == null || due.isEmpty()) {
                return;
            }

            log.info("领取到 {} 个待重试任务（租约 {}s）", due.size(), leaseSeconds);
            processBatch(due, leaseSeconds);
        } catch (RuntimeException e) {
            // 调度器不能因为一轮异常就停摆
            log.error("重试调度轮次失败", e);
        }
    }

    /**
     * 整批租约 = max(配置的租约, 批大小 × 单条预估耗时)。
     *
     * <p>原来固定用 {@code leaseDuration}（5 分钟）覆盖整批 200 条，而处理是顺序的：
     * 目标库慢的时候（每条一个 5s 连接超时）200 条最坏 1000s ≫ 300s，
     * {@code reclaimExpiredLeases} 会把**仍在处理**的任务放回 PENDING，
     * 同一任务被重复重放。{@code uk_retry_active} 只防 PENDING/RUNNING 并存，防不了重放。
     *
     * <p>这只是一个「预估」的下限，真正的兜底是 {@link #renewLease}。
     */
    private long leaseSecondsFor(int batchSize) {
        long base = Math.max(1, props.getLeaseDuration().toSeconds());
        long estimate = (long) batchSize * Math.max(1, props.getEstimatedPerTask().toSeconds());
        return Math.max(base, estimate);
    }

    /**
     * 逐条处理，并周期性续租。
     *
     * <p>批量大小 200 + 初始租约 5 分钟的组合下，只要目标库慢一点，
     * 后半段的任务就会在「租约已过期」的状态下被处理 —— 别的实例（或下一轮
     * 自己的 {@code reclaimExpiredLeases}）已经把它们的副本领走了，
     * 于是同一条消息被写两次。续租把这种情形收敛掉。
     *
     * <p>续租失败（受影响行数与预期不符）说明有人已经抢走，**立即停止本轮**：
     * 继续处理只会制造更多重复重放，剩下的任务留给下一轮。
     */
    private void processBatch(List<RtRetryTask> due, long leaseSeconds) {
        int renewEvery = Math.max(0, props.getRenewLeaseEvery());
        for (int i = 0; i < due.size(); i++) {
            if (renewEvery > 0 && i > 0 && i % renewEvery == 0
                    && !renewLease(due.subList(i, due.size()), leaseSeconds)) {
                log.warn("续租失败，提前结束本轮: 已处理 {}/{}，剩余任务留给下一轮",
                        i, due.size());
                return;
            }
            processOne(due.get(i));
        }
    }

    /** @return true 表示整批仍归本实例所有 */
    private boolean renewLease(List<RtRetryTask> remaining, long leaseSeconds) {
        List<Long> ids = remaining.stream().map(RtRetryTask::getId).toList();
        try {
            int renewed = retryTaskMapper.renewLease(ids, leaseSeconds);
            if (renewed != ids.size()) {
                log.warn("续租只成功 {} / {} 行，说明有任务已被回收", renewed, ids.size());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // 连不上库时不要继续处理：此时审计与状态回写也会失败，
            // 继续跑只会让「处理了一半」的任务看起来仍在推进
            log.warn("续租抛异常，提前结束本轮: {}", e.getMessage());
            return false;
        }
    }

    /** 处理单个任务。包可见，便于单测直接调用。 */
    void processOne(RtRetryTask task) {
        // 重试重放是一个独立的处理入口：把任务绑定的 route/message/version 放进 MDC，
        // 让 RetryReplayer / RetryService 的日志都能按这几个字段检索。
        try (MdcScope ignored = MdcScope.open(
                String.valueOf(task.getRouteId()), task.getMessageId(), task.getConfigVersion())) {
            processOneInScope(task);
        }
    }

    private void processOneInScope(RtRetryTask task) {
        ReplayOutcome outcome;
        try {
            outcome = replayer.replay(task);
        } catch (RuntimeException e) {
            log.error("重放抛出未预期异常: id={} message={}", task.getId(), task.getMessageId(), e);
            outcome = ReplayOutcome.pgFailure(e);
        }

        switch (outcome.kind()) {
            case SUCCESS -> retryService.markSucceeded(task, String.valueOf(outcome.action()));

            case CONFIG_MISSING, PG_FAILURE ->
                    // 系统级问题（可能自愈）：不消耗次数，稍后自动重来。
                    // 刻意不触碰 PgHealthGate —— 闸门的职责是暂停「消费」，
                    // 而重试路径没有对应的消费者可暂停，报进去只会污染闸门状态。
                    retryService.releaseForRetry(task, outcome.errorMessage());

            case CONFIG_GONE -> {
                // 绑定的版本确实没了：重试不会改变结果，但也不该立刻丢 ——
                // 运维可能正要恢复那个版本。给它 configGoneBuffer 轮缓冲，
                // 超过就进 DLQ 并留一条终态错误流水。
                // 没有这条分支时，这类任务每轮都「暂时失败」，next_retry_at 只往后推，
                // 永远不消耗次数、永远进不了 DLQ：任务表只增不减，每分钟刷一条 warn。
                int seen = configGoneCount(task) + 1;
                int limit = Math.max(0, props.getConfigGoneBuffer());
                if (seen > limit) {
                    // 不消耗次数的终态：attempt 保持原值，
                    // 否则会被误读成「试满 10 次才放弃」，而实际是第 1 轮就发现版本没了
                    retryService.markGoneTerminal(task, "CONFIG_GONE", outcome.errorMessage());
                    deadLetterSink.send(RetryMessage.of(task), new FailureMeta(
                            ErrorStage.RETRY.name(), "CONFIG_GONE", outcome.errorMessage(), seen));
                    log.error("绑定配置永久缺失，重试 {} 轮后转入 DLQ: id={} route={} version={}",
                            seen, task.getId(), task.getRouteId(), task.getConfigVersion());
                } else {
                    retryService.holdForConfig(task, outcome.errorMessage(), seen);
                    log.warn("绑定配置缺失，缓冲 {}/{}: id={} route={} version={}",
                            seen, limit, task.getId(), task.getRouteId(), task.getConfigVersion());
                }
            }

            case ROW_FAILURE, DATA_ERROR -> {
                int nextAttempt = (task.getAttempt() == null ? 0 : task.getAttempt()) + 1;
                int maxAttempt = task.getMaxAttempt() == null ? props.getMaxAttempt() : task.getMaxAttempt();
                if (nextAttempt >= maxAttempt) {
                    retryService.markExhausted(task, outcome.errorCode(), outcome.errorMessage());
                    deadLetterSink.send(RetryMessage.of(task), new FailureMeta(
                            ErrorStage.RETRY.name(), outcome.errorCode(), outcome.errorMessage(), nextAttempt));
                } else {
                    retryService.reschedule(task, nextAttempt, outcome.errorCode(), outcome.errorMessage());
                }
            }
        }
    }

    /**
     * 读「已经缓冲过几轮」。
     *
     * <p>不新增列：计数写在 {@code error_message} 的前缀里。
     * 加一列要走迁移，而这个数字纯属运行时诊断信息，
     * 且 {@code error_message} 本来就只给人看 —— 塞一个结构化前缀不会影响任何查询。
     * 前缀不存在时视为 0（老任务从 0 开始算）。
     */
    static int configGoneCount(RtRetryTask task) {
        String msg = task.getErrorMessage();
        if (msg == null || !msg.startsWith(CONFIG_GONE_PREFIX)) {
            return 0;
        }
        int end = msg.indexOf(']');
        if (end < 0) {
            return 0;
        }
        try {
            // 前缀本身已包含 '='，因此从 length() 处开始就是数字。
            // （早先误写成 length() + 1，切出空串 → 解析失败 → 计数恒为 0，
            // 任务永远进不了 DLQ —— 与这个修复要解决的症状一模一样。）
            return Integer.parseInt(msg.substring(CONFIG_GONE_PREFIX.length(), end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static final String CONFIG_GONE_PREFIX = "[configGone=";
}
