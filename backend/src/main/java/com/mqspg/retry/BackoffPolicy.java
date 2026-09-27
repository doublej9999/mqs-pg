package com.mqspg.retry;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 指数退避 + 抖动（PRD §26）。
 *
 * <pre>
 * delay(n) = min(initialDelay × multiplier^(n-1), maxDelay) × (1 ± jitterRatio)
 * </pre>
 *
 * <p><b>为什么要抖动</b>：若一批消息在同一时刻失败（例如下游 PG 抖动），
 * 无抖动时它们会在同一毫秒集体重试，形成周期性冲击波。抖动把重试摊平。
 */
@Component
@RequiredArgsConstructor
public class BackoffPolicy {

    private final RetryProperties props;

    /**
     * @param attempt 已失败的尝试次数（1 表示首次失败，即将安排第 1 次重试）
     */
    public Duration delayFor(int attempt) {
        int n = Math.max(1, attempt);
        double base = props.getInitialDelay().toMillis() * Math.pow(props.getMultiplier(), n - 1);
        double capped = Math.min(base, props.getMaxDelay().toMillis());

        double jitterRatio = Math.max(0d, props.getJitterRatio());
        double factor = 1d + (ThreadLocalRandom.current().nextDouble() * 2d - 1d) * jitterRatio;

        long millis = Math.max(1L, (long) (capped * factor));
        return Duration.ofMillis(millis);
    }

    /** 无抖动的名义延迟，用于日志与文档。 */
    public Duration nominalDelayFor(int attempt) {
        int n = Math.max(1, attempt);
        double base = props.getInitialDelay().toMillis() * Math.pow(props.getMultiplier(), n - 1);
        return Duration.ofMillis((long) Math.min(base, props.getMaxDelay().toMillis()));
    }
}
