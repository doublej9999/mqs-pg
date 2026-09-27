package com.mqspg.raw;

import com.mqspg.mqs.FakeMessage;
import com.mqspg.writer.batch.BufferedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原始留存与分区维护测试（阶段 7）。
 *
 * <p>直接打真实 PostgreSQL：分区 DDL（{@code CREATE / DETACH / DROP PARTITION}）
 * 和 JSONB 转换都无法用内存数据库替代。
 *
 * <p><b>测试写入的时间一律用 {@link LocalDateTime}</b>：分区边界字面量是按会话时区
 * 解释的，用带偏移量的时间会让「这条消息属于哪一天」变得难以推理。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        // 别让每日维护任务在测试期间自己跑
        "mqs-pg.raw.maintenance-cron=0 0 3 1 1 *",
        "mqs-pg.retry.poll-interval=24h"
})
@DisplayName("原始留存与分区")
class RawRetentionTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private RawStore rawStore;
    @Autowired
    private RawPartitionMaintenance maintenance;
    @Autowired
    private JdbcTemplate jdbc;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        settle();
        purgeTestRows();
        today = today();

        // 关键：先确保今天的活跃分区存在，测试写入才不会被塞进 DEFAULT。
        // 一旦 DEFAULT 里落进当天的数据，就再也建不出当天分区了（见 repair 测试）。
        maintenance.ensurePartition(today);
    }

    @AfterEach
    void tearDown() {
        settle();
        purgeTestRows();
        dropPartition(today.plusDays(30));
        dropPartition(today.plusDays(60));
    }

    /**
     * 等异步写入真正落库。
     *
     * <p>只等队列排空是不够的：工作线程可能刚取走一批、还没执行完 INSERT。
     * 不等就删数据，删完之后那批才写进来，测试之间会互相污染。
     */
    private void settle() {
        rawStore.awaitDrained(10_000);
        long last;
        do {
            last = rawStore.writtenCount();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (rawStore.writtenCount() != last);
    }

    private void purgeTestRows() {
        jdbc.update("DELETE FROM raw_message WHERE message_id LIKE 'rawt-%'");
        jdbc.update("DELETE FROM raw_message_default WHERE message_id LIKE 'rawt-%'");
    }

    private LocalDate today() {
        return LocalDate.parse(
                jdbc.queryForObject("SELECT to_char(now()::date, 'YYYY-MM-DD')", String.class));
    }

    private static String partitionName(LocalDate day) {
        return "raw_message_p" + day.format(DATE);
    }

    private boolean partitionExists(LocalDate day) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relname = ? AND n.nspname = current_schema()",
                Integer.class, partitionName(day));
        return n != null && n > 0;
    }

    private void dropPartition(LocalDate day) {
        String name = partitionName(day);
        if (partitionExists(day)) {
            jdbc.execute("ALTER TABLE raw_message DETACH PARTITION " + name);
            jdbc.execute("DROP TABLE " + name);
        }
    }

    /** 按会话时区插入一条原始消息，落到 {@code receiveTime} 所属的分区（或 DEFAULT）。 */
    private void insertRaw(String messageId, LocalDateTime receiveTime) {
        jdbc.update("INSERT INTO raw_message "
                        + "(message_id, route_id, topic, tag, receive_time, config_version, payload) "
                        + "VALUES (?, 1, 'order-topic', 'order.updated', ?, 1, '{\"a\":1}'::jsonb)",
                messageId, receiveTime);
    }

    private long countIn(String relation, String messageId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + relation + " WHERE message_id = ?", Long.class, messageId);
        return n == null ? 0L : n;
    }

    // ------------------------------------------------------------------
    // 分区维护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提前创建未来分区，且重复调用不会重复建")
    void createsFuturePartitionsIdempotently() {
        maintenance.createFuturePartitions();

        assertTrue(partitionExists(today), "今天的分区必须存在");
        assertTrue(partitionExists(today.plusDays(3)), "应提前 3 天创建");

        assertEquals(0, maintenance.createFuturePartitions(), "已存在的分区不应被重复创建");
    }

    @Test
    @DisplayName("过期分区被 DETACH + DROP，保留期内的分区不受影响")
    void dropsExpiredPartitionsOnly() {
        LocalDate old = today.minusDays(60);
        maintenance.ensurePartition(old);
        insertRaw("rawt-old", old.atTime(10, 0));

        assertTrue(partitionExists(old), "前置条件：过期分区应已创建");
        assertEquals(1L, countIn(partitionName(old), "rawt-old"), "前置条件：过期分区里应有数据");

        assertTrue(maintenance.dropExpiredPartitions() >= 1, "应至少回收 1 个过期分区");
        assertFalse(partitionExists(old), "过期分区应已被删除");

        // 保留期内的分区不能被误删
        maintenance.ensurePartition(today);
        maintenance.dropExpiredPartitions();
        assertTrue(partitionExists(today), "保留期内的分区不得被删除");
    }

    /**
     * 回归测试：DEFAULT 里有数据时，必须把数据搬进新分区，而不是永久卡住。
     *
     * <p>这正是首次上线 / 维护未跑时会发生的情况 —— 并且它验证了搬移过程**不丢数据**。
     */
    @Test
    @DisplayName("DEFAULT 分区的数据被搬进新分区，既建出分区也不丢行")
    void repairsDefaultPartitionInsteadOfGettingStuck() {
        LocalDate far = today.plusDays(30);
        dropPartition(far);

        // 这一天没有分区，写入必然落进 DEFAULT
        insertRaw("rawt-default", far.atTime(12, 0));
        assertTrue(maintenance.defaultPartitionRows() >= 1, "前置条件：DEFAULT 里应有该行");

        assertTrue(maintenance.ensurePartition(far), "应搬移数据并建出分区，而不是放弃");

        assertTrue(partitionExists(far), "分区应已建立");
        assertEquals(0L, countIn("raw_message_default", "rawt-default"), "DEFAULT 里的行应已搬走");
        assertEquals(1L, countIn(partitionName(far), "rawt-default"), "数据必须完整搬到新分区，不能丢");
    }

    // ------------------------------------------------------------------
    // 异步留存
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法 JSON 存 payload(JSONB)，非法 JSON 存 payload_raw")
    void storesJsonAndRawText() {
        Instant now = Instant.now();
        rawStore.offerAll(1L, List.of(
                new BufferedMessage(new FakeMessage("rawt-json", "order-topic", "order.updated",
                        "{\"id\":1,\"name\":\"ok\"}", now), 1, now),
                new BufferedMessage(new FakeMessage("rawt-bad", "order-topic", "order.updated",
                        "{ not json", now), 1, now)));

        settle();

        Map<String, Object> json = jdbc.queryForMap(
                "SELECT payload IS NOT NULL AS has_json, payload_raw IS NULL AS raw_is_null, "
                        + "payload ->> 'name' AS name FROM raw_message WHERE message_id = 'rawt-json'");
        assertEquals(Boolean.TRUE, json.get("has_json"));
        assertEquals(Boolean.TRUE, json.get("raw_is_null"));
        assertEquals("ok", json.get("name"), "JSONB 应可被 SQL 直接查询");

        Map<String, Object> bad = jdbc.queryForMap(
                "SELECT payload IS NULL AS json_is_null, payload_raw FROM raw_message "
                        + "WHERE message_id = 'rawt-bad'");
        assertEquals(Boolean.TRUE, bad.get("json_is_null"));
        assertEquals("{ not json", bad.get("payload_raw"));
    }

    @Test
    @DisplayName("未溢出时不丢弃，入队计数正确")
    void countsEnqueuedWithoutDropping() {
        Instant now = Instant.now();
        long before = rawStore.enqueuedCount();

        for (int i = 0; i < 10; i++) {
            rawStore.offer(new RawStore.RawRecord(
                    "rawt-fill-" + i, 1L, "order-topic", "order.updated", now, 1, "{\"i\":" + i + "}", null));
        }

        assertEquals(before + 10, rawStore.enqueuedCount());
        assertEquals(0, rawStore.droppedCount(), "未溢出时不应有丢弃");
    }

    @Test
    @DisplayName("分区清单可读，DEFAULT 行数可查")
    void partitionIntrospectionWorks() {
        maintenance.ensurePartition(today);
        List<Map<String, Object>> partitions = maintenance.listPartitions();

        assertFalse(partitions.isEmpty(), "应至少能看到今天的分区");
        assertTrue(partitions.stream().anyMatch(p -> partitionName(today).equals(p.get("name"))),
                "应能列出今天的分区");
        assertNotNull(partitions.get(0).get("partition_date"), "应能由分区名解析出日期");
        assertTrue(maintenance.defaultPartitionRows() >= 0);
    }
}
