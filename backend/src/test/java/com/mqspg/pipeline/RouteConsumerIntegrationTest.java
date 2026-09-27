package com.mqspg.pipeline;

import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.consumer.ConsumerStateRepository;
import com.mqspg.consumer.PgHealthGate;
import com.mqspg.consumer.RouteConsumer;
import com.mqspg.mqs.mock.MockMqsBroker;
import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsConsumerFactory;
import com.mqspg.writer.batch.BatchManager;
import com.mqspg.writer.batch.BatchProperties;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实消费循环的集成测试：启动 {@link RouteConsumer} 后台线程，
 * 验证「拉取 → 绑定版本 → 按条数/时间触发 → 写入 → ACK」整条链路。
 *
 * <p>批次参数被压到很小（size=3 / flush=200ms）以便快速触发两种条件。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        // 原始留存是旁路，与消费循环断言无关；开着会往 raw_message 写数据
        "mqs-pg.raw.enabled=false",
        "mqs-pg.batch.size=3",
        "mqs-pg.batch.flush-interval=200ms",
        "mqs-pg.batch.pull-size=10"
})
@DisplayName("消费循环")
class RouteConsumerIntegrationTest {

    private static final long ROUTE_ID = 1L;
    private static final String TOPIC = "order-topic";
    private static final String TAG = "order.updated";

    @Autowired
    private MockMqsBroker broker;
    @Autowired
    private MqsConsumerFactory consumerFactory;
    @Autowired
    private ConfigRegistry registry;
    @Autowired
    private BatchManager batchManager;
    @Autowired
    private BatchProperties batchProps;
    @Autowired
    private ConsumerStateRepository stateRepository;
    @Autowired
    private PgHealthGate healthGate;
    @Autowired
    private TargetDataSourceRegistry dataSources;
    @Autowired
    private JdbcTemplate jdbc;

    private RouteConsumer routeConsumer;
    private Thread thread;

    @BeforeEach
    @AfterEach
    void cleanup() throws InterruptedException {
        stop();
        jdbc.update("DELETE FROM biz_demo.orders WHERE id BETWEEN 980001 AND 980099");
        jdbc.update("DELETE FROM mqs_pg.rt_retry_task WHERE message_id LIKE 'it-%'");
        jdbc.update("DELETE FROM mqs_pg.rt_error_record WHERE message_id LIKE 'it-%'");
        broker.reset();
    }

    private void stop() throws InterruptedException {
        if (routeConsumer != null) {
            routeConsumer.close();
            routeConsumer = null;
        }
        if (thread != null) {
            thread.join(5000);
            thread = null;
        }
    }

    private void start() {
        MqsConsumer consumer = consumerFactory.create(
                new ConsumerSpec(ROUTE_ID, TOPIC, TAG, "loop-test-group", 10, Duration.ofSeconds(30)));
        routeConsumer = new RouteConsumer(
                ROUTE_ID, consumer, registry, batchManager, batchProps,
                stateRepository, healthGate, dataSources.jdbc(1L), Duration.ofMillis(100));
        thread = new Thread(routeConsumer, "test-route-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    @Test
    @DisplayName("投递后被自动消费、写入 PG 且全部 ACK，队列清空")
    void consumesWritesAndAcks() {
        long id = 980040L;
        broker.publish(TOPIC, TAG,
                """
                        {"id":%d,"name":"loop","amount":55.00,"status":"PAID","updatedAt":"2026-03-01T09:00:00+08:00"}"""
                        .formatted(id),
                "it-loop-1");

        start();

        assertTrue(await(() -> "loop".equals(nameOf(id)), Duration.ofSeconds(10)),
                "10 秒内应写入目标表");
        assertTrue(await(() -> broker.inflightCount() == 0, Duration.ofSeconds(5)),
                "处理完成后不应有在途消息");
        assertEquals(0, broker.pendingCount(TOPIC), "队列应清空（说明已 ACK，而非重新可见）");
        assertEquals(1L, routeConsumer.ackedCount());
        assertEquals(0L, routeConsumer.noAckCount());
        assertTrue(routeConsumer.batchCount() >= 1);
    }

    @Test
    @DisplayName("时间触发：消息数不足 batch.size 也会在 flush-interval 内落库（PRD §9）")
    void timeTriggerFlushesPartialBatch() {
        long id = 980041L;
        broker.publish(TOPIC, TAG,
                """
                        {"id":%d,"name":"slow","amount":1.00,"status":"CREATED","updatedAt":"2026-03-02T09:00:00+08:00"}"""
                        .formatted(id),
                "it-loop-2");

        start();

        // batch.size=3，只有 1 条消息 —— 只能靠时间触发
        assertTrue(await(() -> "slow".equals(nameOf(id)), Duration.ofSeconds(10)),
                "单条消息应在 flush-interval 后被时间条件触发写入");
        assertTrue(await(() -> broker.inflightCount() == 0, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("坏消息不阻塞后续好消息（真实循环下验证 PRD §49）")
    void badMessageDoesNotStallTheLoop() {
        long badId = 980042L;
        long goodId = 980043L;

        // 枚举非法 —— DATA 级错误，进 DLQ
        broker.publish(TOPIC, TAG,
                """
                        {"id":%d,"name":"bad","amount":1.00,"status":"NOPE","updatedAt":"2026-03-03T09:00:00+08:00"}"""
                        .formatted(badId),
                "it-loop-3");
        broker.publish(TOPIC, TAG,
                """
                        {"id":%d,"name":"after-bad","amount":2.00,"status":"PAID","updatedAt":"2026-03-03T10:00:00+08:00"}"""
                        .formatted(goodId),
                "it-loop-4");

        start();

        assertTrue(await(() -> "after-bad".equals(nameOf(goodId)), Duration.ofSeconds(10)),
                "坏消息之后的好消息必须照常写入");
        assertTrue(await(() -> broker.inflightCount() == 0, Duration.ofSeconds(5)),
                "坏消息也必须被 ACK 并留下 DLQ 流水");
        assertNotNull(jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'it-loop-3'", Integer.class));
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private String nameOf(long id) {
        var got = jdbc.queryForList("SELECT name FROM biz_demo.orders WHERE id = ?", String.class, id);
        return got.isEmpty() ? null : got.get(0);
    }
}
