package com.mqspg.raw;

import com.mqspg.common.persistence.JsonbTypeHandler;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.writer.batch.BufferedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原始消息留存（tech-design §11，PRD §29）。
 *
 * <p><b>核心取舍：留存是旁路，不是主链路的一部分。</b>
 * 因此：
 * <ul>
 *   <li>入队**非阻塞**（{@code offer}），队列满时丢弃并计数告警 ——
 *       绝不让「留个底」这件事把消费拖停；</li>
 *   <li>写入在自己的工作线程里攒批进行，与消费线程解耦；</li>
 *   <li>丢弃是**有意的设计**，不是缺陷：留存的目的是排障参考，
 *       而消费与落库的可靠性由 ACK 不变量保证，不依赖留存。</li>
 * </ul>
 *
 * <p>重复留存不做去重：同一消息因 ACK 丢失被重投时会留下多行。
 * 这是一份**追加式日志**，去重检查交给查询侧按 {@code message_id} 聚合。
 */
@Slf4j
@Component
public class RawStore implements AutoCloseable {

    private static final String INSERT_SQL = """
            INSERT INTO raw_message
                (message_id, route_id, topic, tag, receive_time, config_version, payload, payload_raw)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""";

    private final RawProperties props;
    private final JdbcTemplate jdbc;

    private final BlockingQueue<RawRecord> queue;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public RawStore(RawProperties props, JdbcTemplate jdbc) {
        this.props = props;
        this.jdbc = jdbc;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, props.getQueueCapacity()));
        if (props.isEnabled()) {
            startWorkers();
        } else {
            log.info("原始消息留存已关闭（mqs-pg.raw.enabled=false）");
        }
    }

    /** 入队一条记录。**永不阻塞**。 */
    public void offer(RawRecord record) {
        if (!props.isEnabled()) {
            return;
        }
        if (queue.offer(record)) {
            enqueued.incrementAndGet();
        } else {
            long n = dropped.incrementAndGet();
            // 只在首次和每 1000 次丢弃时告警，避免刷屏
            if (n == 1 || n % 1000 == 0) {
                log.warn("原始留存队列已满，已累计丢弃 {} 条（消费主链路不受影响）", n);
            }
        }
    }

    /** 批量入队。批次始终属于单一路由，因此 routeId 由调用方给出。 */
    public void offerAll(long routeId, List<BufferedMessage> batch) {
        if (!props.isEnabled() || batch == null || batch.isEmpty()) {
            return;
        }
        for (BufferedMessage m : batch) {
            offer(toRecord(routeId, m));
        }
    }

    private static RawRecord toRecord(long routeId, BufferedMessage buffered) {
        MqsMessage handle = buffered.handle();
        byte[] body = handle.body();

        String json = null;
        String raw = null;
        if (body != null && body.length > 0) {
            String text = new String(body, StandardCharsets.UTF_8);
            try {
                JsonbTypeHandler.mapper().readTree(body);
                json = text;      // 合法 JSON → 存 JSONB，可被 SQL 直接查询
            } catch (Exception e) {
                raw = text;       // 解析失败 → 原样存文本，排障时仍能看到原文
            }
        }
        return new RawRecord(
                handle.messageId(),
                routeId,
                handle.topic(),
                handle.tag(),
                buffered.receivedAt(),
                buffered.configVersion(),
                json,
                raw);
    }

    // ------------------------------------------------------------------

    private void startWorkers() {
        for (int i = 0; i < Math.max(1, props.getWorkerCount()); i++) {
            Thread t = new Thread(this::runWorker, "raw-store-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        log.info("原始留存已启动: workers={} queueCapacity={} batchSize={} flushInterval={}",
                workers.size(), props.getQueueCapacity(), props.getBatchSize(), props.getFlushInterval());
    }

    private void runWorker() {
        List<RawRecord> batch = new ArrayList<>(props.getBatchSize());
        while (running) {
            try {
                RawRecord first = queue.poll(props.getFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, props.getBatchSize() - 1);
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 留存写入失败不能影响主链路，也不能杀死工作线程
                log.error("原始留存写入失败，本批 {} 条被丢弃", batch.size(), e);
                batch.clear();
            }
        }
        // 停机前把剩余的清空
        try {
            queue.drainTo(batch, props.getBatchSize());
            while (!batch.isEmpty()) {
                flush(batch);
                batch.clear();
                queue.drainTo(batch, props.getBatchSize());
            }
        } catch (RuntimeException e) {
            log.error("停机时清空留存队列失败", e);
        }
    }

    private void flush(List<RawRecord> batch) {
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RawRecord r = batch.get(i);
                ps.setString(1, r.messageId());
                ps.setLong(2, r.routeId());
                ps.setString(3, r.topic());
                ps.setString(4, r.tag());
                ps.setObject(5, OffsetDateTime.ofInstant(r.receiveTime(), ZoneOffset.UTC));
                if (r.configVersion() == null) {
                    ps.setNull(6, Types.INTEGER);
                } else {
                    ps.setInt(6, r.configVersion());
                }
                if (r.payloadJson() == null) {
                    ps.setNull(7, Types.OTHER);
                } else {
                    ps.setString(7, r.payloadJson());
                }
                if (r.payloadRaw() == null) {
                    ps.setNull(8, Types.VARCHAR);
                } else {
                    ps.setString(8, r.payloadRaw());
                }
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
        written.addAndGet(batch.size());
    }

    // ------------------------------------------------------------------

    /** 等待队列排空，供测试使用。 */
    public boolean awaitDrained(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }

    public long enqueuedCount() {
        return enqueued.get();
    }

    public long writtenCount() {
        return written.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    @Override
    public void close() {
        running = false;
        for (Thread t : workers) {
            t.interrupt();
        }
        for (Thread t : workers) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 一条待留存的原始消息。{@code payloadJson} 与 {@code payloadRaw} 至多有一个非空。 */
    public record RawRecord(
            String messageId,
            long routeId,
            String topic,
            String tag,
            Instant receiveTime,
            Integer configVersion,
            String payloadJson,
            String payloadRaw) {
    }
}
