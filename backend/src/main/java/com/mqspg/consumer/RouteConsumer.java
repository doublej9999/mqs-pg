package com.mqspg.consumer;

import com.mqspg.common.model.RouteConfig;
import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.writer.batch.BatchManager;
import com.mqspg.writer.batch.BatchProperties;
import com.mqspg.writer.batch.BatchResult;
import com.mqspg.writer.batch.BufferedMessage;
import com.mqspg.writer.batch.MessageDisposition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个路由的消费循环（tech-design §6 / §7.1）。
 *
 * <p>循环节奏：
 * <pre>
 *   PG 健康闸门关闭？ → 探测，未恢复则等待
 *   拉取一批消息     → 立刻读取当前 ACTIVE 版本并绑定到每条消息（PRD §18）
 *   条数或时间触发？ → 交给 BatchManager 处理
 *   应用处置结论     → 该 ACK 的 ACK，不该 ACK 的留着让 MQ 重投
 * </pre>
 *
 * <p><b>版本绑定点</b>：绑定发生在 {@code receive} 返回之后、进入缓冲区之前。
 * 之后即便配置被重新发布，本批消息仍用旧快照 —— 这正是 PRD §19 的要求。
 */
@Slf4j
public class RouteConsumer implements Runnable, AutoCloseable {

    private final long routeId;
    private final MqsConsumer consumer;
    private final ConfigRegistry registry;
    private final BatchManager batchManager;
    private final BatchProperties batchProps;
    private final ConsumerStateRepository stateRepository;
    private final PgHealthGate healthGate;
    private final JdbcTemplate probeJdbc;
    private final Duration probeInterval;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong noAck = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();

    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile String pauseReason;

    public RouteConsumer(long routeId,
                         MqsConsumer consumer,
                         ConfigRegistry registry,
                         BatchManager batchManager,
                         BatchProperties batchProps,
                         ConsumerStateRepository stateRepository,
                         PgHealthGate healthGate,
                         JdbcTemplate probeJdbc,
                         Duration probeInterval) {
        this.routeId = routeId;
        this.consumer = consumer;
        this.registry = registry;
        this.batchManager = batchManager;
        this.batchProps = batchProps;
        this.stateRepository = stateRepository;
        this.healthGate = healthGate;
        this.probeJdbc = probeJdbc;
        this.probeInterval = probeInterval;
    }

    @Override
    public void run() {
        log.info("Consumer 启动: route={} pullSize={} batchSize={} flushInterval={} invisible={}",
                routeId, batchProps.getPullSize(), batchProps.getSize(),
                batchProps.getFlushInterval(), batchProps.getInvisibleDuration());

        List<BufferedMessage> buffer = new ArrayList<>(batchProps.getSize());
        long lastFlushNanos = System.nanoTime();

        try {
            while (running) {
                if (paused && !tryRecover()) {
                    sleepQuietly(probeInterval);
                    continue;
                }

                List<MqsMessage> pulled;
                try {
                    pulled = consumer.receive(batchProps.getPullSize(), batchProps.getInvisibleDuration());
                } catch (RuntimeException e) {
                    log.error("拉取消息失败，退避后重试: route={}", routeId, e);
                    sleepQuietly(Duration.ofSeconds(1));
                    continue;
                }

                if (pulled != null && !pulled.isEmpty()) {
                    // ★ 版本绑定点：以「接收时刻」的 ACTIVE 版本为准（PRD §18）
                    int version = registry.activeVersion(routeId);
                    Instant now = Instant.now();
                    for (MqsMessage m : pulled) {
                        buffer.add(new BufferedMessage(m, version, now));
                    }
                    received.addAndGet(pulled.size());
                }

                if (shouldFlush(buffer, lastFlushNanos)) {
                    flush(buffer);
                    buffer.clear();
                    lastFlushNanos = System.nanoTime();
                } else if (pulled == null || pulled.isEmpty()) {
                    // 空转会让 CPU 白烧；退避到 flush 间隔的一小部分
                    sleepQuietly(shorter(batchProps.getFlushInterval()));
                }
            }
        } catch (Throwable t) {
            log.error("Consumer 异常退出: route={}", routeId, t);
            stateRepository.markError(routeId, "Consume loop crashed: " + t.getMessage(), -1);
        } finally {
            if (!buffer.isEmpty()) {
                // 优雅停机：把缓冲区处理完，避免消息在不可见期结束后被重复投递
                log.info("停机前处理缓冲区剩余 {} 条: route={}", buffer.size(), routeId);
                flush(buffer);
            }
            consumer.close();
            log.info("Consumer 已停止: route={}", routeId);
        }
    }

    private boolean shouldFlush(List<BufferedMessage> buffer, long lastFlushNanos) {
        if (buffer.isEmpty()) {
            return false;
        }
        if (buffer.size() >= batchProps.getSize()) {
            return true;
        }
        long elapsedMs = (System.nanoTime() - lastFlushNanos) / 1_000_000L;
        return elapsedMs >= batchProps.getFlushInterval().toMillis();
    }

    private void flush(List<BufferedMessage> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        BatchResult result;
        try {
            result = batchManager.process(routeId, List.copyOf(buffer));
        } catch (RuntimeException e) {
            // BatchManager 不应抛出；真抛了说明有未预期缺陷。
            // 此时绝不能 ACK —— 让 MQ 重投是唯一安全的选择。
            log.error("批次处理抛出未预期异常，本批 {} 条均不 ACK: route={}", buffer.size(), routeId, e);
            noAck.addAndGet(buffer.size());
            return;
        }

        batches.incrementAndGet();
        applyDispositions(result);

        if (result.aborted()) {
            pause("PG 级故障: " + result.abortReason());
        } else {
            stateRepository.markSuccess(routeId);
        }
    }

    /** ACK 的唯一执行点。 */
    private void applyDispositions(BatchResult result) {
        for (MessageDisposition d : result.dispositions()) {
            if (!d.kind().shouldAck()) {
                noAck.incrementAndGet();
                log.warn("不 ACK（将等待 MQ 重新投递）: route={} message={} 原因={}",
                        routeId, d.handle().messageId(), d.detail());
                continue;
            }
            try {
                consumer.ack(d.handle());
                acked.incrementAndGet();
            } catch (RuntimeException e) {
                // ACK 失败是安全的：消息会重投，幂等 Upsert 保证最终状态正确（PRD §28）
                log.warn("ACK 失败，消息可能重复投递: route={} message={} 原因={}",
                        routeId, d.handle().messageId(), e.getMessage());
            }
        }
        if (result.count(com.mqspg.writer.batch.Disposition.ACK_DEFERRED) > 0
                || result.count(com.mqspg.writer.batch.Disposition.ACK_DLQ) > 0) {
            log.info("批次存在延迟/终态处置: route={} 重试={} DLQ={} 成功={}",
                    routeId,
                    result.count(com.mqspg.writer.batch.Disposition.ACK_DEFERRED),
                    result.count(com.mqspg.writer.batch.Disposition.ACK_DLQ),
                    result.count(com.mqspg.writer.batch.Disposition.ACK));
        }
    }

    /** 闸门关闭时的主动探测。只有真的写成功才说明 PG 恢复。 */
    private boolean tryRecover() {
        try {
            probeJdbc.queryForObject("SELECT 1", Integer.class);
            healthGate.recordSuccess(datasourceId());
            if (healthGate.isOpen(datasourceId())) {
                log.warn("PG 探测成功，恢复消费: route={}", routeId);
                paused = false;
                pauseReason = null;
                stateRepository.markRunning(routeId, registry.activeVersion(routeId));
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            healthGate.recordFailure(datasourceId(), e);
            log.debug("PG 探测失败，继续暂停: route={} 原因={}", routeId, e.getMessage());
            return false;
        }
    }

    private void pause(String reason) {
        if (paused) {
            return;
        }
        paused = true;
        pauseReason = reason;
        int version = registry.activeVersion(routeId);
        stateRepository.markPaused(routeId, reason, version);
        log.error("Consumer 已暂停: route={} 原因={}", routeId, reason);
    }

    private Long datasourceId() {
        RouteConfig cfg = registry.active(routeId);
        return cfg == null ? null : cfg.target().datasourceId();
    }

    private static Duration shorter(Duration d) {
        long ms = Math.max(20L, Math.min(d.toMillis(), 200L));
        return Duration.ofMillis(ms);
    }

    private void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() {
        running = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public String pauseReason() {
        return pauseReason;
    }

    public long receivedCount() {
        return received.get();
    }

    public long ackedCount() {
        return acked.get();
    }

    public long noAckCount() {
        return noAck.get();
    }

    public long batchCount() {
        return batches.get();
    }

    /** 供测试同步驱动一个批次，不依赖后台线程。 */
    public void flushNow(List<BufferedMessage> buffer) {
        flush(buffer);
    }
}
