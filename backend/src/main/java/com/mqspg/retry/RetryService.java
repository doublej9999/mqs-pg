package com.mqspg.retry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ErrorSeverity;
import com.mqspg.common.error.ErrorStage;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.retry.entity.RtErrorRecord;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtErrorRecordMapper;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 重试任务的持久化（ADR-02）。
 *
 * <p><b>本类是 ACK 不变量的另一半</b>：
 * <pre>
 * ack(message) ⟹ (PG 已提交) ∨ (∃ 持久化的 rt_retry_task 行)
 * </pre>
 * 因此 {@link #scheduleDeferred} **必须**在调用方 ACK 之前成功返回。
 * 它的 insert 走配置库的自动提交，方法返回即已落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final RtRetryTaskMapper retryTaskMapper;
    private final RtErrorRecordMapper errorRecordMapper;
    private final RetryProperties props;
    private final BackoffPolicy backoff;

    /**
     * 登记一条待重试任务。
     *
     * @param attempt 本次已失败的尝试次数（首次失败传 1）
     * @return 任务主键
     */
    public Long scheduleDeferred(MqsMessage message,
                                 long routeId,
                                 int configVersion,
                                 String group,
                                 int attempt,
                                 Throwable error) {
        ErrorCode code = codeOf(error);
        OffsetDateTime now = OffsetDateTime.now();

        int effectiveAttempt = Math.max(1, attempt);
        int maxAttempt = props.getMaxAttempt();

        RtRetryTask task = new RtRetryTask();
        task.setRouteId(routeId);
        task.setConfigVersion(configVersion);
        task.setMessageId(message.messageId());
        task.setTopic(message.topic());
        task.setTag(message.tag());
        task.setPayload(message.body());
        task.setErrorStage(code.getStage().name());
        task.setErrorCode(code.name());
        task.setErrorMessage(abbreviate(error.getMessage()));
        task.setAttempt(effectiveAttempt);
        task.setMaxAttempt(maxAttempt);
        task.setNextRetryAt(now.plus(backoff.delayFor(effectiveAttempt)));
        task.setStatus("PENDING");
        task.setLastErrorAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        retryTaskMapper.insert(task);
        log.info("已登记重试: route={} message={} attempt={}/{} code={} nextRetryAt={}",
                routeId, message.messageId(), effectiveAttempt, maxAttempt, code, task.getNextRetryAt());
        return task.getId();
    }

    /** 记录一条错误流水（可用于非终态与终态）。 */
    public void recordError(MqsMessage message,
                            long routeId,
                            int configVersion,
                            String errorCode,
                            String errorStage,
                            String errorMessage,
                            int retryCount,
                            boolean isFinal) {
        OffsetDateTime now = OffsetDateTime.now();
        RtErrorRecord record = new RtErrorRecord();
        record.setRouteId(routeId);
        record.setConfigVersion(configVersion);
        record.setMessageId(message.messageId());
        record.setTopic(message.topic());
        record.setTag(message.tag());
        record.setPayload(message.body());
        record.setErrorTime(now);
        record.setErrorStage(errorStage);
        record.setErrorCode(errorCode);
        record.setErrorMessage(abbreviate(errorMessage));
        record.setRetryCount(retryCount);
        record.setFinalFlag(isFinal);
        record.setCreatedAt(now);
        errorRecordMapper.insert(record);
    }

    /** 记录终态失败（DLQ）。 */
    public void recordFinalFailure(MqsMessage message, long routeId, int configVersion, Throwable error) {
        ErrorCode code = codeOf(error);
        recordError(message, routeId, configVersion, code.name(), code.getStage().name(),
                error.getMessage(), props.getMaxAttempt(), true);
        log.error("消息进入 DLQ: route={} message={} code={} reason={}",
                routeId, message.messageId(), code, error.getMessage());
    }

    // ==================================================================
    // 重试任务的状态迁移（阶段 6）
    //
    // 注意：这几个方法**不能**用 updateById。MyBatis-Plus 的 updateById 默认
    // 忽略 null 字段（FieldStrategy.NOT_NULL），而「释放租约」的语义恰恰是
    // 把 lease_until 置为 NULL —— 用 updateById 会静默地什么都不做，
    // 留下一行 PENDING 却带着过期租约的任务。因此统一改用 UpdateWrapper 显式赋值。
    // ==================================================================

    /** 重放成功。{@code SKIPPED_OLD_VERSION} 也算成功 —— 有更新的数据胜出即为正确终态。 */
    public void markSucceeded(RtRetryTask task, String detail) {
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "SUCCEEDED")
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getErrorMessage, truncate(detail))
                .set(RtRetryTask::getUpdatedAt, OffsetDateTime.now()), task.getId());
        log.info("重试成功: id={} route={} message={} attempt={} 结果={}",
                task.getId(), task.getRouteId(), task.getMessageId(), task.getAttempt(), detail);
    }

    /** 消耗一次机会后重新排队。 */
    public void reschedule(RtRetryTask task, int nextAttempt, String errorCode, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime next = now.plus(backoff.delayFor(nextAttempt));
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "PENDING")
                .set(RtRetryTask::getAttempt, nextAttempt)
                .set(RtRetryTask::getErrorCode, errorCode)
                .set(RtRetryTask::getErrorMessage, truncate(errorMessage))
                .set(RtRetryTask::getLastErrorAt, now)
                .set(RtRetryTask::getNextRetryAt, next)
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now), task.getId());
        log.warn("重试失败，已重新排队: id={} message={} attempt={}/{} code={} nextRetryAt={}",
                task.getId(), task.getMessageId(), nextAttempt, task.getMaxAttempt(), errorCode, next);
    }

    /**
     * 系统级问题，**不消耗**重试次数，短暂后退再试。
     *
     * <p>配置缺失、PG 连不上都属于这一类。若把它们计入次数，
     * 一次数据库维护窗口就能把大批本可成功的消息推进 DLQ。
     */
    public void releaseForRetry(RtRetryTask task, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        long backoffMs = Math.max(1000L, props.getPollInterval().toMillis() * 3);
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "PENDING")
                .set(RtRetryTask::getErrorMessage, truncate(reason))
                .set(RtRetryTask::getLastErrorAt, now)
                .set(RtRetryTask::getNextRetryAt, now.plusNanos(backoffMs * 1_000_000L))
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now), task.getId());
        log.warn("系统级问题，暂缓重试（不消耗次数）: id={} message={} attempt={} 原因={}",
                task.getId(), task.getMessageId(), task.getAttempt(), reason);
    }

    /** 次数耗尽 → 终态 DLQ，并留下可查询的终态错误流水。 */
    public void markExhausted(RtRetryTask task, String errorCode, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "DLQ")
                .set(RtRetryTask::getAttempt, task.getMaxAttempt())
                .set(RtRetryTask::getErrorCode, errorCode)
                .set(RtRetryTask::getErrorMessage, truncate(errorMessage))
                .set(RtRetryTask::getLastErrorAt, now)
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now), task.getId());

        MqsMessage view = RetryMessage.of(task);
        recordError(view, task.getRouteId(), task.getConfigVersion(),
                errorCode, ErrorStage.RETRY.name(), errorMessage,
                task.getMaxAttempt(), true);

        log.error("重试次数耗尽，进入 DLQ: id={} route={} message={} attempt={} code={} 原因={}",
                task.getId(), task.getRouteId(), task.getMessageId(),
                task.getMaxAttempt(), errorCode, errorMessage);
    }

    /** 手工取消（控制台操作）。 */
    public void cancel(RtRetryTask task, String reason) {
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "CANCELLED")
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getErrorMessage, truncate(reason))
                .set(RtRetryTask::getUpdatedAt, OffsetDateTime.now()), task.getId());
    }

    /** 把任务提前到「现在到期」，由调度器在下一轮领取。 */
    public void makeDueNow(RtRetryTask task) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<RtRetryTask> update = transition(task.getId())
                .set(RtRetryTask::getStatus, "PENDING")
                .set(RtRetryTask::getNextRetryAt, now)
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now);

        if ("DLQ".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            // 人工重放终态任务：追加一轮额度。历史次数保留，只为审计。
            int newMax = (task.getAttempt() == null ? 0 : task.getAttempt()) + props.getMaxAttempt();
            update.set(RtRetryTask::getMaxAttempt, newMax);
            log.info("人工重放终态任务: id={} message={} 新上限={}",
                    task.getId(), task.getMessageId(), newMax);
        }
        apply(update, task.getId());
    }

    private LambdaUpdateWrapper<RtRetryTask> transition(Long id) {
        return new LambdaUpdateWrapper<RtRetryTask>().eq(RtRetryTask::getId, id);
    }

    private void apply(LambdaUpdateWrapper<RtRetryTask> update, Long id) {
        int updated = retryTaskMapper.update(null, update);
        if (updated != 1) {
            log.warn("重试任务状态更新影响 {} 行（期望 1）: id={}", updated, id);
        }
    }

    /** 待重试积压（控制台用）。 */
    public List<RtRetryTask> backlog(String status, int limit) {
        return retryTaskMapper.selectList(new LambdaQueryWrapper<RtRetryTask>()
                .eq(status != null && !status.isBlank(), RtRetryTask::getStatus, status)
                .orderByAsc(RtRetryTask::getNextRetryAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    private static String truncate(String s) {
        return abbreviate(s);
    }

    /**
     * 错误是否应当进入重试队列。
     *
     * <p>DATA 类默认直接进 DLQ —— 转换/解析失败由消息内容决定，重试不会改变结果。
     * FATAL（PG 连接级故障）不在此处理：那类故障既不 ACK 也不落重试表，
     * 直接让 MQ 重新投递（PRD §24 / §50）。
     */
    public boolean shouldRetry(Throwable error) {
        ErrorCode code = codeOf(error);
        if (code.getSeverity() == ErrorSeverity.DATA) {
            return props.isRetryDataErrors();
        }
        return code.getSeverity() == ErrorSeverity.TRANSIENT;
    }

    public static ErrorCode codeOf(Throwable error) {
        return (error instanceof ProcessingException pe) ? pe.getCode() : ErrorCode.INTERNAL_ERROR;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
    }
}
