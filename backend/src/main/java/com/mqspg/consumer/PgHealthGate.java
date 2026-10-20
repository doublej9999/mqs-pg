package com.mqspg.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mqspg.metrics.MqsMetrics;

import lombok.extern.slf4j.Slf4j;

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
    private final MqsMetrics metrics;
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    /** 已注册过 gauge 的数据源，避免重复注册。 */
    private final Set<Long> gauged = ConcurrentHashMap.newKeySet();

    public PgHealthGate(@Value("${mqs-pg.pg-health.failure-threshold:3}") int failureThreshold,
                        @Value("${mqs-pg.pg-health.recovery-threshold:2}") int recoveryThreshold,
                        MqsMetrics metrics) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.recoveryThreshold = Math.max(1, recoveryThreshold);
        this.metrics = metrics;
    }

    /** 是否允许继续消费。未知数据源默认放行（首次写入会自然暴露问题）。 */
    public boolean isOpen(long datasourceId) {
        State s = states.get(datasourceId);
        return s == null || s.open;
    }

    private double openValue(long datasourceId) {
        return isOpen(datasourceId) ? 1.0 : 0.0;
    }

    private void ensureGauge(long datasourceId) {
        if (gauged.add(datasourceId)) {
            metrics.gauge("mqspg.pg.health.open", datasourceId, this::openValue,
                    "datasource", String.valueOf(datasourceId));
        }
    }

    public void recordSuccess(long datasourceId) {
        ensureGauge(datasourceId);
        State s = states.computeIfAbsent(datasourceId, k -> new State());
        synchronized (s) {
            s.consecutiveFailures = 0;
            s.consecutiveSuccesses++;
            if (!s.open && s.consecutiveSuccesses >= recoveryThreshold) {
                s.open = true;
                s.lastError = null;
                metrics.count("mqspg.pg.gate.opened", "datasource", String.valueOf(datasourceId));
                log.warn("PG 健康闸门已恢复: datasourceId={}（连续 {} 次成功）",
                        datasourceId, s.consecutiveSuccesses);
            }
        }
    }

    public void recordFailure(long datasourceId, Throwable cause) {
        ensureGauge(datasourceId);
        State s = states.computeIfAbsent(datasourceId, k -> new State());
        synchronized (s) {
            s.consecutiveSuccesses = 0;
            s.consecutiveFailures++;
            s.lastError = cause == null ? null : cause.getMessage();
            if (s.open && s.consecutiveFailures >= failureThreshold) {
                s.open = false;
                metrics.count("mqspg.pg.gate.closed", "datasource", String.valueOf(datasourceId));
                log.error("PG 健康闸门已关闭，暂停消费: datasourceId={}（连续 {} 次失败）原因={}",
                        datasourceId, s.consecutiveFailures, s.lastError);
            }
        }
    }

    /** 最近一次失败原因，用于写入 rt_consumer_state.reason。 */
    public String lastError(long datasourceId) {
        State s = states.get(datasourceId);
        if (s == null) {
            return null;
        }
        synchronized (s) {
            return s.lastError;
        }
    }

    public void reset(long datasourceId) {
        states.remove(datasourceId);
    }

    /**
     * 所有已知数据源的闸门状态快照（datasourceId → 是否放行），供健康检查聚合。
     *
     * <p>只反映"已经出现过写入尝试"的数据源；从未被触碰的数据源不会出现在这里
     * （未知默认放行，不构成不健康）。
     */
    public Map<Long, Boolean> snapshot() {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        states.forEach((id, state) -> {
            synchronized (state) {
                result.put(id, state.open);
            }
        });
        return result;
    }

    private static final class State {
        private boolean open = true;
        private int consecutiveFailures;
        private int consecutiveSuccesses;
        private String lastError;
    }
}
