package com.mqspg.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PG 健康闸门（tech-design §6.2，PRD §24 / §50）。
 *
 * <p>语义：
 * <ul>
 *   <li>连续 {@code failureThreshold} 次 PG 级失败 → 关闭闸门，暂停消费；</li>
 *   <li>关闭后连续 {@code recoveryThreshold} 次探测成功 → 重新打开。</li>
 * </ul>
 *
 * <p>为什么要「连续」计数而不是累计：单次抖动不该停掉消费，
 * 而恢复也必须确认是稳定恢复而非偶发成功。
 */
@Slf4j
@Component
public class PgHealthGate {

    private final int failureThreshold;
    private final int recoveryThreshold;
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    public PgHealthGate(@Value("${mqs-pg.pg-health.failure-threshold:3}") int failureThreshold,
                        @Value("${mqs-pg.pg-health.recovery-threshold:2}") int recoveryThreshold) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.recoveryThreshold = Math.max(1, recoveryThreshold);
    }

    /** 是否允许继续消费。未知数据源默认放行（首次写入会自然暴露问题）。 */
    public boolean isOpen(Long datasourceId) {
        State s = states.get(datasourceId);
        return s == null || s.open;
    }

    public void recordSuccess(Long datasourceId) {
        State s = states.computeIfAbsent(datasourceId, k -> new State());
        synchronized (s) {
            s.consecutiveFailures = 0;
            s.consecutiveSuccesses++;
            if (!s.open && s.consecutiveSuccesses >= recoveryThreshold) {
                s.open = true;
                s.lastError = null;
                log.warn("PG 健康闸门已恢复: datasourceId={}（连续 {} 次成功）",
                        datasourceId, s.consecutiveSuccesses);
            }
        }
    }

    public void recordFailure(Long datasourceId, Throwable cause) {
        State s = states.computeIfAbsent(datasourceId, k -> new State());
        synchronized (s) {
            s.consecutiveSuccesses = 0;
            s.consecutiveFailures++;
            s.lastError = cause == null ? null : cause.getMessage();
            if (s.open && s.consecutiveFailures >= failureThreshold) {
                s.open = false;
                log.error("PG 健康闸门已关闭，暂停消费: datasourceId={}（连续 {} 次失败）原因={}",
                        datasourceId, s.consecutiveFailures, s.lastError);
            }
        }
    }

    /** 最近一次失败原因，用于写入 rt_consumer_state.reason。 */
    public String lastError(Long datasourceId) {
        State s = states.get(datasourceId);
        if (s == null) {
            return null;
        }
        synchronized (s) {
            return s.lastError;
        }
    }

    public void reset(Long datasourceId) {
        states.remove(datasourceId);
    }

    private static final class State {
        private boolean open = true;
        private int consecutiveFailures;
        private int consecutiveSuccesses;
        private String lastError;
    }
}
