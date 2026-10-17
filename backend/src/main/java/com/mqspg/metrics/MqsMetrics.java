package com.mqspg.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * 业务指标统一入口（里程碑 B1）。
 *
 * <p>此前项目虽然引入了 {@code micrometer-registry-prometheus}，但只暴露了 JVM / HTTP 默认指标，
 * 没有一条业务指标，"{@code /actuator/prometheus}" 看不出消费、重试、留存是否正常。
 * 本类把散落在各组件里的原子计数器接到 Micrometer。
 *
 * <p><b>为什么只依赖 {@code MeterRegistry}</b>：指标要打在最底层的组件里（writer / consumer /
 * raw / retry）。若本类反向依赖这些组件（例如为了拉取 gauge），就会形成 Spring bean 循环依赖。
 * 因此这里只做"推送"（counter / timer）与一个通用 gauge 注册入口，
 * 各组件在自己的 {@code @PostConstruct} 里注册自己的 gauge，依赖方向始终是单向的。
 *
 * <p>命名约定：{@code mqspg.<domain>.<metric>}，路由级别的指标用 {@code route} 标签，
 * 目标表用 {@code target}，数据源用 {@code datasource}。
 */
@Component
public class MqsMetrics {

    private final MeterRegistry registry;

    /**
     * 缓存已创建的 counter / timer，避免在消息级热路径上反复走 registry 的查找。
     * 键 = 指标名 + 标签值（标签顺序固定即可）。
     */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public MqsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // Counter
    // ------------------------------------------------------------------

    /** 计数 +1。 */
    public void count(String name, String... tags) {
        counter(name, tags).increment();
    }

    /** 计数 +n。 */
    public void count(String name, long amount, String... tags) {
        if (amount == 0) {
            return;
        }
        counter(name, tags).increment(amount);
    }

    private Counter counter(String name, String... tags) {
        String key = name + Arrays.toString(tags);
        return counters.computeIfAbsent(key,
                k -> Counter.builder(name).tags(tags).register(registry));
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

    /** 记录一次耗时。 */
    public void record(String name, Duration duration, String... tags) {
        String key = name + Arrays.toString(tags);
        Timer timer = timers.computeIfAbsent(key,
                k -> Timer.builder(name).tags(tags).register(registry));
        timer.record(duration);
    }

    // ------------------------------------------------------------------
    // Gauge
    // ------------------------------------------------------------------

    /**
     * 注册一个 gauge（可重复调用，Micrometer 对同 id 幂等）。
     *
     * <p>{@code strongReference(true)} 不可省略：默认 Micrometer 只弱引用被测对象，
     * 若传入的是内联 lambda，可能在下一次 GC 后变成 NaN。
     *
     * @param state 被测状态对象（通常是 {@code this} 或一个 {@code AtomicLong}）
     */
    public <T> void gauge(String name, T state, ToDoubleFunction<T> value, String... tags) {
        Gauge.builder(name, state, value).tags(tags).strongReference(true).register(registry);
    }
}
