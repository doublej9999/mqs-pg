package com.mqspg.pipeline;

import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.mqs.mock.MockMqsBroker;
import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsConsumerFactory;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.writer.batch.BatchManager;
import com.mqspg.writer.batch.BatchResult;
import com.mqspg.writer.batch.BufferedMessage;
import com.mqspg.writer.batch.Disposition;
import com.mqspg.writer.batch.MessageDisposition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端管线测试：投递 → 拉取 → 转换 → 折叠 → MERGE → ACK 判定。
 *
 * <p>不启动后台线程（{@code auto-start=false}），直接驱动 {@link BatchManager}，
 * 让断言完全确定。真实消费循环由 {@code RouteConsumerIntegrationTest} 覆盖。
 *
 * <p><b>依赖本地 PostgreSQL</b>（与 PgWriterTest 同）。测试数据 id 落在
 * {@code 980001..980099}，用前后清理保证可重复执行。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "mqs-pg.consumer.auto-start=false")
@DisplayName("端到端管线")
class PipelineIntegrationTest {

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
    private JdbcTemplate jdbc;

    private MqsConsumer consumer;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM biz_demo.orders WHERE id BETWEEN 980001 AND 980099");
        jdbc.update("DELETE FROM mqs_pg.rt_retry_task WHERE message_id LIKE 'it-%'");
        jdbc.update("DELETE FROM mqs_pg.rt_error_record WHERE message_id LIKE 'it-%'");
        broker.reset();
        if (consumer != null) {
            consumer.close();
            consumer = null;
        }
    }

    // ------------------------------------------------------------------
    // 乱序与去重
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一批次内同主键的多条消息折叠为一行，保留 update_time 最大者")
    void newestVersionWinsWithinOneBatch() {
        long id = 980001L;
        publish("it-1", body(id, "first", "10.00", "CREATED", "2026-01-01T10:00:00+08:00"));
        publish("it-2", body(id, "second", "20.00", "PAID", "2026-01-01T11:00:00+08:00"));
        publish("it-3", body(id, "third", "30.00", "CANCELLED", "2026-01-01T12:00:00+08:00"));
        // 故意把「最旧」的一条放在最后投递：消费顺序不等于时间顺序
        publish("it-4", body(id, "stale", "5.00", "CREATED", "2026-01-01T09:00:00+08:00"));

        BatchResult result = process();

        assertFalse(result.aborted());
        assertEquals(4, result.count(Disposition.ACK), "四条消息都应被 ACK");
        assertEquals(0, result.count(Disposition.NO_ACK));

        assertEquals("third", nameOf(id));
        assertEquals(0, new BigDecimal("30.00").compareTo(amountOf(id)));
        assertEquals(3, statusOf(id), "CANCELLED → 3");

        // 折叠后只写一行，验证确实没有产生重复键
        assertEquals(1, count(id));
    }

    @Test
    @DisplayName("旧版本消息后到不会覆盖新数据，但依然被 ACK（SKIPPED_OLD_VERSION）")
    void staleMessageDoesNotOverwriteNewerRow() {
        long id = 980002L;
        publish("it-10", body(id, "newest", "77.00", "PAID", "2026-05-05T12:00:00+08:00"));
        BatchResult first = process();
        assertEquals(1, first.count(Disposition.ACK));
        assertEquals("newest", nameOf(id));

        // 再投一条 update_time 更早的
        publish("it-11", body(id, "stale", "1.00", "CREATED", "2026-05-05T11:00:00+08:00"));
        BatchResult second = process();

        assertEquals(1, second.count(Disposition.ACK), "被跳过的消息也必须 ACK，否则会无限重投");
        assertEquals("newest", nameOf(id), "单调守卫必须拦住旧版本");
        assertEquals(0, new BigDecimal("77.00").compareTo(amountOf(id)));
        assertEquals(2, statusOf(id), "PAID → 2");
    }

    @Test
    @DisplayName("update_time 相同时由消费顺序决定（PRD §8：后到者胜）")
    void equalUpdateTimeIsDecidedByConsumptionOrder() {
        long id = 980003L;
        String sameTime = "2026-06-06T08:00:00+08:00";
        publish("it-20", body(id, "aaa", "1.00", "CREATED", sameTime));
        publish("it-21", body(id, "bbb", "2.00", "PAID", sameTime));

        BatchResult result = process();
        assertFalse(result.aborted());
        assertEquals(2, result.count(Disposition.ACK));

        assertEquals("bbb", nameOf(id), "同一 update_time 时，消费顺序在后者胜出");
        assertEquals(2, statusOf(id));
    }

    @Test
    @DisplayName("重复投递同一条消息是幂等的：第二次写入被判定为 SKIPPED 或 UPDATED，行值不变")
    void redeliveryIsIdempotent() {
        long id = 980004L;
        byte[] payload = body(id, "dup", "9.99", "PAID", "2026-07-07T07:00:00+08:00").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        broker.publish(TOPIC, TAG, new String(payload, java.nio.charset.StandardCharsets.UTF_8), "it-30");
        BatchResult first = process();
        assertEquals(1, first.count(Disposition.ACK));
        assertEquals("dup", nameOf(id));

        // 同一条消息再次投递（ACK 丢失导致的重复）
        broker.publish(TOPIC, TAG, new String(payload, java.nio.charset.StandardCharsets.UTF_8), "it-31");
        BatchResult second = process();

        assertEquals(1, second.count(Disposition.ACK));
        assertEquals("dup", nameOf(id));
        assertEquals(0, new BigDecimal("9.99").compareTo(amountOf(id)));
        assertEquals(1, count(id));
    }

    // ------------------------------------------------------------------
    // 错误路由
    // ------------------------------------------------------------------

    @Test
    @DisplayName("必填字段缺失 → 不写库、记 DLQ、ACK；且不阻塞同批次的好消息（PRD §49）")
    void invalidRecordGoesToDlqAndDoesNotBlockValidOnes() {
        long goodId = 980010L;
        publish("it-40", body(goodId, "good", "12.00", "PAID", "2026-08-08T08:00:00+08:00"));
        // 缺 id（required）→ MISSING_REQUIRED_FIELD，DATA 级，重试无意义
        publish("it-41", "{\"name\":\"no-id\",\"amount\":\"1.00\",\"updatedAt\":\"2026-08-08T08:00:00+08:00\"}");

        BatchResult result = process();

        assertFalse(result.aborted());
        assertEquals(1, result.count(Disposition.ACK), "好消息正常写入");
        assertEquals(1, result.count(Disposition.ACK_DLQ), "坏消息必须 ACK，否则会永久堵住 Topic");
        assertEquals(2, result.ackedCount(), "两条都应被 ACK：一条写入、一条进 DLQ");
        assertEquals(0, result.unackedCount());

        assertEquals("good", nameOf(goodId), "同批次的好消息必须照常写入");

        Integer dlqCount = jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'it-41' AND is_final = true",
                Integer.class);
        assertEquals(1, dlqCount, "坏消息应留下终态错误流水");
    }

    @Test
    @DisplayName("行级 PG 数据错误被隔离：好行正常写入，坏行落重试表后 ACK（ACK 不变量）")
    void rowLevelFailureIsIsolatedAndPersistedBeforeAck() {
        long goodId = 980020L;
        long badId = 980021L;

        publish("it-50", body(goodId, "fine", "10.00", "PAID", "2026-09-09T09:00:00+08:00"));
        // NUMERIC(18,2) 放不下 20 位整数 → SQLSTATE 22003，数据级错误
        publish("it-51", body(badId, "overflow", "99999999999999999999", "PAID", "2026-09-09T09:00:00+08:00"));

        BatchResult result = process();

        assertFalse(result.aborted(), "数据级错误不该被判定为 PG 故障");
        assertEquals(1, result.count(Disposition.ACK), "好行正常写入");
        assertEquals(1, result.count(Disposition.ACK_DEFERRED), "坏行落重试表后允许 ACK");
        assertEquals(2, result.ackedCount());

        assertEquals("fine", nameOf(goodId), "好行必须写入");
        assertNull(nameOf(badId), "坏行不应写入");

        // 关键不变量：ACK 之前，重试任务必须已经持久化
        Integer retryCount = jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_retry_task WHERE message_id = 'it-51' AND status = 'PENDING'",
                Integer.class);
        assertEquals(1, retryCount, "坏行必须已生成待重试任务");

        String code = jdbc.queryForObject(
                "SELECT error_code FROM mqs_pg.rt_retry_task WHERE message_id = 'it-51'", String.class);
        assertEquals("PG_CONSTRAINT_VIOLATION", code);
    }

    @Test
    @DisplayName("非法 JSON → DLQ + ACK，不写库")
    void malformedJsonGoesToDlq() {
        publish("it-60", "{ this is not json ");

        BatchResult result = process();

        assertEquals(1, result.count(Disposition.ACK_DLQ));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'it-60' AND error_code = 'JSON_PARSE_ERROR'",
                Integer.class));
    }

    @Test
    @DisplayName("枚举值不在映射表中 → DLQ + ACK")
    void unknownEnumGoesToDlq() {
        long id = 980030L;
        publish("it-70", body(id, "weird", "1.00", "REFUNDED", "2026-10-10T10:00:00+08:00"));

        BatchResult result = process();

        assertEquals(1, result.count(Disposition.ACK_DLQ));
        assertNull(nameOf(id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'it-70' AND error_code = 'ENUM_MAPPING_ERROR'",
                Integer.class));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 拉一次、按接收时刻绑定版本、跑一个批次，并把该 ACK 的 ACK 掉。 */
    private BatchResult process() {
        MqsConsumer c = consumer();
        List<MqsMessage> messages = c.receive(1000, Duration.ofSeconds(30));
        assertFalse(messages.isEmpty(), "队列里应有消息");

        int version = registry.activeVersion(ROUTE_ID);
        Instant now = Instant.now();
        List<BufferedMessage> batch = messages.stream()
                .map(m -> new BufferedMessage(m, version, now))
                .toList();

        BatchResult result = batchManager.process(ROUTE_ID, batch);
        for (MessageDisposition d : result.dispositions()) {
            if (d.kind().shouldAck()) {
                c.ack(d.handle());
            }
        }
        return result;
    }

    private MqsConsumer consumer() {
        if (consumer == null) {
            consumer = consumerFactory.create(new ConsumerSpec(
                    ROUTE_ID, TOPIC, TAG, "test-group", 1000, Duration.ofSeconds(30)));
        }
        return consumer;
    }

    private void publish(String messageId, String json) {
        broker.publish(TOPIC, TAG, json, messageId);
    }

    private static String body(long id, String name, String amount, String status, String updatedAt) {
        return """
                {"id":%d,"name":"%s","amount":%s,"status":"%s","updatedAt":"%s"}"""
                .formatted(id, name, amount, status, updatedAt);
    }

    // --- 库内断言 ---

    private String nameOf(long id) {
        List<String> got = jdbc.queryForList(
                "SELECT name FROM biz_demo.orders WHERE id = ?", String.class, id);
        return got.isEmpty() ? null : got.get(0);
    }

    private BigDecimal amountOf(long id) {
        return jdbc.queryForObject(
                "SELECT amount FROM biz_demo.orders WHERE id = ?", BigDecimal.class, id);
    }

    private Integer statusOf(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM biz_demo.orders WHERE id = ?", Integer.class, id);
    }

    private int count(long id) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM biz_demo.orders WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("前置条件：demo 配置已就位")
    void demoConfigIsPresent() {
        assertNotNull(registry.get(ROUTE_ID, registry.activeVersion(ROUTE_ID)));
        assertTrue(registry.activeVersion(ROUTE_ID) >= 1);
    }
}
