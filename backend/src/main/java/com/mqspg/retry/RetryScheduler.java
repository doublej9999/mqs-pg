package com.mqspg.retry;

import com.mqspg.common.error.ErrorStage;
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

            List<RtRetryTask> due = retryTaskMapper.claimDue(
                    props.getBatchSize(), Math.max(1, props.getLeaseDuration().toSeconds()));
            if (due == null || due.isEmpty()) {
                return;
            }

            log.info("领取到 {} 个待重试任务", due.size());
            for (RtRetryTask task : due) {
                processOne(task);
            }
        } catch (RuntimeException e) {
            // 调度器不能因为一轮异常就停摆
            log.error("重试调度轮次失败", e);
        }
    }

    /** 处理单个任务。包可见，便于单测直接调用。 */
    void processOne(RtRetryTask task) {
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
                    // 系统级问题：不消耗次数，稍后自动重来。
                    // 刻意不触碰 PgHealthGate —— 闸门的职责是暂停「消费」，
                    // 而重试路径没有对应的消费者可暂停，报进去只会污染闸门状态。
                    retryService.releaseForRetry(task, outcome.errorMessage());

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
}
