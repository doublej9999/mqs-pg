package com.mqspg.metrics;

import com.mqspg.consumer.PgHealthGate;
import com.mqspg.raw.RawStore;
import com.mqspg.retry.RetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务健康指示器（里程碑 B2）。
 *
 * <p>默认的 {@code /actuator/health} 只检查"进程活着"，无法回答运维真正关心的问题：
 * <b>消费还转不转？重试是不是堆爆了？留存还在丢吗？</b> 本指示器把这三件事聚合进来。
 *
 * <p>状态映射（阈值全部走配置，避免写死）：
 * <ul>
 *   <li>任一数据源的 PG 健康闸门关闭 → {@code DOWN}（消费已停，是硬故障）；</li>
 *   <li>重试积压（PENDING + RUNNING）超阈值 → {@code OUT_OF_SERVICE}（还能消费，但积压在恶化）；</li>
 *   <li>原始留存丢弃率超阈值（且样本足够）→ {@code OUT_OF_SERVICE}
 *       （留存是旁路，丢弃不致命，只表示可观测性在退化）。</li>
 * </ul>
 *
 * <p>刻意用 {@code OUT_OF_SERVICE} 而不是 {@code DOWN} 表达"降级但可用"，
 * 这样 K8s 之类只看 UP/DOWN 的探针不会因为留存丢弃就把实例摘掉。
 */
@Slf4j
@Component("mqsPg")
public class MqsPgHealthIndicator implements HealthIndicator {

    private final PgHealthGate healthGate;
    private final RetryService retryService;
    private final RawStore rawStore;

    private final long backlogThreshold;
    private final double dropRatioThreshold;
    private final long dropSampleMin;

    /** 积压缓存超过多久算过期（只标注 stale，不据此判 DOWN）。 */
    private final Duration backlogStaleAfter;

    public MqsPgHealthIndicator(PgHealthGate healthGate,
                                RetryService retryService,
                                RawStore rawStore,
                                @Value("${mqs-pg.health.retry-backlog-threshold:10000}") long backlogThreshold,
                                @Value("${mqs-pg.health.raw-drop-ratio-threshold:0.1}") double dropRatioThreshold,
                                @Value("${mqs-pg.health.raw-drop-sample-min:1000}") long dropSampleMin,
                                @Value("${mqs-pg.health.retry-backlog-stale-after:45s}") Duration backlogStaleAfter) {
        this.healthGate = healthGate;
        this.retryService = retryService;
        this.rawStore = rawStore;
        this.backlogThreshold = backlogThreshold;
        this.dropRatioThreshold = dropRatioThreshold;
        this.dropSampleMin = dropSampleMin;
        this.backlogStaleAfter = backlogStaleAfter;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyDown = false;
        boolean degraded = false;

        // 1) PG 健康闸门：已知数据源里有没有关闭的
        Map<Long, Boolean> gates = healthGate.snapshot();
        List<Long> closed = new ArrayList<>();
        gates.forEach((id, open) -> {
            if (!open) {
                closed.add(id);
            }
        });
        Map<String, Object> gateDetail = new LinkedHashMap<>();
        gateDetail.put("monitored", gates.keySet());
        gateDetail.put("closed", closed);
        details.put("pgGate", gateDetail);
        if (!closed.isEmpty()) {
            anyDown = true;
        }

        // 2) 重试积压 —— 读内存缓存的 gauge，**不查库**
        //
        // 这里原来直接调 retryService.summary()，那是一次 GROUP BY status。
        // K8s / 负载均衡通常每 5–10s 探一次 /actuator/health，
        // 等于把聚合查询挂到了探针频率上，DB 不可用时 health 还会跟着变慢。
        // 明明已有 15s 刷新的 gauge，用缓存即可。
        long pending = 0;
        long dlq = 0;
        boolean stale = false;
        try {
            Map<String, Object> summary = retryService.cachedSummary(backlogStaleAfter);
            pending = num(summary.get("PENDING")) + num(summary.get("RUNNING"));
            dlq = num(summary.get("DLQ"));
            stale = Boolean.TRUE.equals(summary.get("stale"));
        } catch (RuntimeException e) {
            // 读内存不该失败，但 health 端点绝不抛 500
            log.warn("健康检查读取重试积压失败: {}", e.getMessage());
            details.put("retryBacklogError", "积压读取失败");
            degraded = true;
        }
        Map<String, Object> backlogDetail = new LinkedHashMap<>();
        backlogDetail.put("pending", pending);
        backlogDetail.put("dlq", dlq);
        backlogDetail.put("threshold", backlogThreshold);
        // 过期只标注、不判 DOWN：探针要回答「能否服务」，不是「指标是否新鲜」。
        // 判 DOWN 会让一次指标刷新失败直接导致实例被摘掉。
        backlogDetail.put("stale", stale);
        details.put("retryBacklog", backlogDetail);
        if (pending > backlogThreshold) {
            degraded = true;
        }

        // 3) 原始留存丢弃率（累计口径；样本不足时不判定，避免启动初期误报）
        long written = rawStore.writtenCount();
        long dropped = rawStore.droppedCount();
        long total = written + dropped;
        double ratio = total == 0 ? 0.0 : (double) dropped / total;
        Map<String, Object> rawDetail = new LinkedHashMap<>();
        rawDetail.put("written", written);
        rawDetail.put("dropped", dropped);
        rawDetail.put("ratio", ratio);
        rawDetail.put("threshold", dropRatioThreshold);
        details.put("rawDrop", rawDetail);
        if (total >= dropSampleMin && ratio > dropRatioThreshold) {
            degraded = true;
        }

        Status status = anyDown ? Status.DOWN
                : degraded ? Status.OUT_OF_SERVICE
                : Status.UP;
        return Health.status(status).withDetails(details).build();
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
