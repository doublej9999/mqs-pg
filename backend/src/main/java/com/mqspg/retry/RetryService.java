package com.mqspg.retry;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ErrorSeverity;
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
