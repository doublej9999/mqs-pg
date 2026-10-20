package com.mqspg.retry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ErrorSeverity;
import com.mqspg.common.error.ErrorStage;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.metrics.MqsMetrics;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.retry.entity.RtErrorRecord;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtErrorRecordMapper;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final MqsMetrics metrics;

    /** 重试积压的 gauge 持有者；由定时任务刷新，抓取时不打 DB。 */
    private final Map<String, AtomicLong> backlogGauges = new ConcurrentHashMap<>();

    /** 最近一次成功刷新积压指标的时间；从未刷新过则为 null（健康检查据此判断过期）。 */
    private final AtomicReference<OffsetDateTime> lastRefreshAt = new AtomicReference<>();

    /**
     * 注册积压指标 holder。
     *
     * <p>积压量是 DB 里的"当前状态"，用 gauge；但若让 gauge 直接去查库，
     * 每次 Prometheus 抓取都会打一次 group by。这里改为定时把 {@link #summary()}
     * 的结果写进 {@link AtomicLong}，gauge 只读内存。
     */
    @PostConstruct
    void initBacklogMetrics() {
        for (String status : List.of("PENDING", "RUNNING", "DLQ")) {
            AtomicLong holder = new AtomicLong();
            backlogGauges.put(status, holder);
            metrics.gauge("mqspg.retry.backlog", holder, AtomicLong::doubleValue, "status", status);
        }
    }

    @Scheduled(fixedDelayString = "${mqs-pg.metrics.refresh-interval:15s}")
    public void refreshBacklogMetrics() {
        try {
            Map<String, Object> counts = summary();
            backlogGauges.forEach((status, holder) -> {
                Object v = counts.get(status);
                holder.set(v instanceof Number n ? n.longValue() : 0L);
            });
            lastRefreshAt.set(OffsetDateTime.now());
        } catch (RuntimeException e) {
            // 指标刷新失败不应影响主链路
            log.debug("刷新重试积压指标失败: {}", e.getMessage());
        }
    }

    /**
     * 只读的内存积压快照 —— 供健康检查这类高频调用使用，**不查库**。
     *
     * <p>此前 {@link com.mqspg.metrics.MqsPgHealthIndicator} 直接调 {@link #summary()}，
     * 而后者是一条 {@code GROUP BY status}。K8s / 负载均衡通常每 5–10s 探一次
     * {@code /actuator/health}，等于把一次聚合查询挂到了探针频率上；
     * DB 不可用时 health 还会跟着变慢。明明已经有一套 15s 刷新的内存 gauge，却没用上。
     *
     * <p>返回值的 {@code stale} 表示数据是否已过期（超过 {@code staleAfter}）。
     * 过期的积压数据**不作为 DOWN 的依据** —— 探针要回答的是「进程能否服务」，
     * 而不是「指标是否新鲜」；把过期判成 DOWN 会让一次指标刷新失败直接触发实例被摘。
     *
     * @param staleAfter 多久没刷新就算过期
     */
    public Map<String, Object> cachedSummary(Duration staleAfter) {
        Map<String, Object> out = new LinkedHashMap<>();
        backlogGauges.forEach((status, holder) -> out.put(status, holder.get()));
        OffsetDateTime at = lastRefreshAt.get();
        boolean stale = at == null
                || staleAfter != null && at.isBefore(OffsetDateTime.now().minus(staleAfter));
        out.put("stale", stale);
        out.put("refreshedAt", at == null ? null : at.toString());
        return out;
    }

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

        // 幂等入队：若同一消息已有活跃重试任务（ACK 丢失导致的重投），
        // 刷新它而不是撞唯一索引 —— 否则会陷入"不 ACK → 重投 → 再撞"的死循环。
        Long id = retryTaskMapper.upsertActive(task);
        task.setId(id);
        log.info("已登记重试: id={} route={} message={} attempt={}/{} code={} nextRetryAt={}",
                id, routeId, message.messageId(), effectiveAttempt, maxAttempt, code, task.getNextRetryAt());
        return id;
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

    /**
     * 绑定配置永久缺失，**缓冲**等待人工修复。
     *
     * <p>与 {@link #releaseForRetry} 的区别只有一处：把「已经缓冲了几轮」记进
     * {@code error_message} 的前缀，调度器下一轮据此判断是否该进 DLQ。
     * 不新增列是因为这个数字纯属运行时诊断信息，加列要走迁移；
     * 而 {@code error_message} 本来就只给人看，塞一个结构化前缀不影响任何查询。
     *
     * @param seen 本次是第几轮（从 1 开始）
     */
    public void holdForConfig(RtRetryTask task, String reason, int seen) {
        OffsetDateTime now = OffsetDateTime.now();
        long backoffMs = Math.max(1000L, props.getPollInterval().toMillis() * 3);
        String marked = "[configGone=%d] %s".formatted(seen, reason);
        apply(transition(task.getId())
                .set(RtRetryTask::getStatus, "PENDING")
                .set(RtRetryTask::getErrorCode, "CONFIG_GONE")
                .set(RtRetryTask::getErrorMessage, truncate(marked))
                .set(RtRetryTask::getLastErrorAt, now)
                .set(RtRetryTask::getNextRetryAt, now.plusNanos(backoffMs * 1_000_000L))
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now), task.getId());
        log.warn("绑定配置缺失，缓冲等待恢复（第 {} 轮）: id={} message={} 原因={}",
                seen, task.getId(), task.getMessageId(), reason);
    }

    /** 次数耗尽 → 终态 DLQ，并留下可查询的终态错误流水。 */
    public void markExhausted(RtRetryTask task, String errorCode, String errorMessage) {
        markTerminal(task, errorCode, errorMessage, true);
    }

    /**
     * 终态 DLQ，**不消耗**（也不改写）重试次数。
     *
     * <p>用于「绑定配置永久缺失」这类与次数无关的终态：任务的 {@code attempt}
     * 保持原值，{@code max_attempt} 也不动 —— 把 attempt 写成 maxAttempt 会让人
     * 以为「试满 10 次才放弃」，而实际是第 1 次就发现版本没了。
     */
    public void markGoneTerminal(RtRetryTask task, String errorCode, String errorMessage) {
        markTerminal(task, errorCode, errorMessage, false);
    }

    private void markTerminal(RtRetryTask task, String errorCode, String errorMessage,
                              boolean consumeAttempt) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<RtRetryTask> update = transition(task.getId())
                .set(RtRetryTask::getStatus, "DLQ")
                .set(RtRetryTask::getErrorCode, errorCode)
                .set(RtRetryTask::getErrorMessage, truncate(errorMessage))
                .set(RtRetryTask::getLastErrorAt, now)
                .set(RtRetryTask::getLeaseUntil, null)
                .set(RtRetryTask::getUpdatedAt, now);
        if (consumeAttempt) {
            update.set(RtRetryTask::getAttempt, task.getMaxAttempt());
        }
        apply(update, task.getId());

        MqsMessage view = RetryMessage.of(task);
        recordError(view, task.getRouteId(), task.getConfigVersion(),
                errorCode, ErrorStage.RETRY.name(), errorMessage,
                task.getMaxAttempt(), true);

        log.error("重试进入终态 DLQ: id={} route={} message={} attempt={} code={} 原因={}",
                task.getId(), task.getRouteId(), task.getMessageId(),
                task.getAttempt(), errorCode, errorMessage);
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

    /**
     * 按状态汇总，供控制台概览。
     *
     * <p>固定输出全部状态（计数为 0 也要出现）：前端据此渲染固定的状态卡片，
     * 缺键会让页面出现「有时少一个标签」的闪烁。
     */
    public Map<String, Object> summary() {
        List<Map<String, Object>> rows = retryTaskMapper.selectMaps(
                new QueryWrapper<RtRetryTask>()
                        .select("status", "count(*) as cnt")
                        .groupBy("status"));

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "RUNNING", "SUCCEEDED", "DLQ", "CANCELLED")) {
            counts.put(s, 0L);
        }
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            if (status != null) {
                counts.put(String.valueOf(status), row.get("cnt"));
            }
        }
        return counts;
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
