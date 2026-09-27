package com.mqspg.retry;

import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
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
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试调度器测试（阶段 6）。
 *
 * <p>调度轮询被推到 24 小时，测试直接调用 {@code processOne}，
 * 断言完全确定，不受后台线程影响。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h"
})
@DisplayName("重试调度器")
class RetrySchedulerTest {

    @Autowired
    private RetryScheduler scheduler;
    @Autowired
    private RetryService retryService;
    @Autowired
    private RtRetryTaskMapper retryTaskMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mqs_pg.rt_retry_task WHERE message_id LIKE 'rtt-%'");
        jdbc.update("DELETE FROM mqs_pg.rt_error_record WHERE message_id LIKE 'rtt-%'");
        jdbc.update("DELETE FROM biz_demo.orders WHERE id BETWEEN 980001 AND 980099");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("重放成功 → SUCCEEDED，且数据真的落库")
    void successfulReplayMarksSucceeded() {
        long id = 980060L;
        RtRetryTask task = insert("rtt-1", payload(id, "retried", "42.00", "PAID",
                "2026-04-04T10:00:00+08:00"), 1, 10, 1);

        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("SUCCEEDED", after.getStatus());
        assertNull(after.getLeaseUntil());
        assertEquals("retried", nameOf(id));
        assertEquals(0, new BigDecimal("42.00").compareTo(amountOf(id)));
    }

    @Test
    @DisplayName("仍然失败的数据错误 → 消耗一次机会并重新排队，next_retry_at 推后")
    void persistentDataErrorReschedules() {
        long id = 980061L;
        // NUMERIC(18,2) 溢出 → 每次重放都会失败
        RtRetryTask task = insert("rtt-2", payload(id, "overflow", "99999999999999999999", "PAID",
                "2026-04-05T10:00:00+08:00"), 1, 10, 1);

        OffsetDateTime before = OffsetDateTime.now();
        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("PENDING", after.getStatus(), "未耗尽时应重新排队");
        assertEquals(2, after.getAttempt(), "数据错误应消耗一次机会");
        assertTrue(after.getNextRetryAt().isAfter(before), "下次重试时间必须推后");
        assertNull(after.getLeaseUntil(), "重新排队必须释放租约");
        assertEquals("PG_CONSTRAINT_VIOLATION", after.getErrorCode());
    }

    @Test
    @DisplayName("次数耗尽 → DLQ，并写入终态错误流水")
    void exhaustedTaskGoesToDlq() {
        long id = 980062L;
        RtRetryTask task = insert("rtt-3", payload(id, "overflow", "99999999999999999999", "PAID",
                "2026-04-06T10:00:00+08:00"), 9, 10, 1);

        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("DLQ", after.getStatus());
        assertEquals(10, after.getAttempt());

        Integer finals = jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'rtt-3' AND is_final = true",
                Integer.class);
        assertEquals(1, finals, "DLQ 必须留下可查询的终态流水");
    }

    @Test
    @DisplayName("绑定版本不存在 → 不消耗重试次数（系统级问题）")
    void missingConfigDoesNotConsumeAttempt() {
        long id = 980063L;
        // 版本 999 不存在。若实现「回退到 ACTIVE 版本」，这次重放就会成功 ——
        // 因此本用例同时证明了重放严格使用绑定版本（PRD §19）。
        RtRetryTask task = insert("rtt-4", payload(id, "ghost", "1.00", "PAID",
                "2026-04-07T10:00:00+08:00"), 3, 10, 999);

        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("PENDING", after.getStatus());
        assertEquals(3, after.getAttempt(), "配置缺失不得消耗重试次数");
        assertNotNull(after.getNextRetryAt());
        assertNull(nameOf(id), "绑定版本不可用时绝不能落库");
    }

    @Test
    @DisplayName("重放时库中已有更新数据 → 判定成功（SKIPPED_OLD_VERSION 也是正确终态）")
    void replayLosingToNewerDataCountsAsSuccess() {
        long id = 980064L;
        // 库里先放一条更新的数据
        jdbc.update("""
                        INSERT INTO biz_demo.orders (id, name, amount, status, source, update_time)
                        VALUES (?, 'newer', 100.00, 2, 'SEED', ?::timestamptz)""",
                id, "2026-04-08T12:00:00+08:00");

        // 重试任务携带的是一条更旧的报文
        RtRetryTask task = insert("rtt-5", payload(id, "older", "1.00", "CREATED",
                "2026-04-08T09:00:00+08:00"), 1, 10, 1);

        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("SUCCEEDED", after.getStatus(),
                "被单调守卫拦住说明有更新数据胜出，这是成功而非失败");
        assertEquals("newer", nameOf(id), "旧重试不得覆盖新数据");
    }

    @Test
    @DisplayName("取到租约过期的任务能重新排队（实例崩溃恢复）")
    void expiredLeaseIsReclaimed() {
        RtRetryTask task = insert("rtt-6", payload(980065L, "x", "1.00", "PAID",
                "2026-04-09T10:00:00+08:00"), 1, 10, 1);
        task.setStatus("RUNNING");
        task.setLeaseUntil(OffsetDateTime.now().minusMinutes(10));
        retryTaskMapper.updateById(task);

        int reclaimed = retryTaskMapper.reclaimExpiredLeases();

        assertTrue(reclaimed >= 1);
        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("PENDING", after.getStatus());
        assertNull(after.getLeaseUntil());
    }

    @Test
    @DisplayName("claimDue 只领取到期的 PENDING 任务，且把状态置为 RUNNING 并加租约")
    void claimDueOnlyClaimsDuePendingTasks() {
        RtRetryTask due = insert("rtt-7", payload(980066L, "a", "1.00", "PAID",
                "2026-04-10T10:00:00+08:00"), 1, 10, 1);
        due.setStatus("PENDING");
        due.setNextRetryAt(OffsetDateTime.now().minusSeconds(5));
        retryTaskMapper.updateById(due);

        RtRetryTask future = insert("rtt-8", payload(980067L, "b", "1.00", "PAID",
                "2026-04-10T11:00:00+08:00"), 1, 10, 1);
        future.setStatus("PENDING");
        future.setNextRetryAt(OffsetDateTime.now().plusHours(3));
        retryTaskMapper.updateById(future);

        List<RtRetryTask> claimed = retryTaskMapper.claimDue(50, 300);
        List<String> claimedIds = claimed.stream().map(RtRetryTask::getMessageId).toList();

        assertTrue(claimedIds.contains("rtt-7"), "到期的任务应被领取");
        assertTrue(!claimedIds.contains("rtt-8"), "未到期的任务不该被领取");

        RtRetryTask after = retryTaskMapper.selectById(due.getId());
        assertEquals("RUNNING", after.getStatus());
        assertNotNull(after.getLeaseUntil());
    }

    @Test
    @DisplayName("人工重放 DLQ 任务会追加一轮额度并立即到期")
    void manualReplayOfDlqTaskGrantsNewBudget() {
        RtRetryTask task = insert("rtt-9", payload(980068L, "c", "1.00", "PAID",
                "2026-04-11T10:00:00+08:00"), 10, 10, 1);
        task.setStatus("DLQ");
        retryTaskMapper.updateById(task);

        retryService.makeDueNow(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("PENDING", after.getStatus());
        assertEquals(20, after.getMaxAttempt(), "人工重放应追加一轮额度（10 → 20）");
        assertTrue(!after.getNextRetryAt().isAfter(OffsetDateTime.now().plusSeconds(1)));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 直接用 Mapper 造一条「已被领取」的重试任务。 */
    private RtRetryTask insert(String messageId, String body, int attempt, int maxAttempt, int version) {
        OffsetDateTime now = OffsetDateTime.now();
        RtRetryTask t = new RtRetryTask();
        t.setRouteId(1L);
        t.setConfigVersion(version);
        t.setMessageId(messageId);
        t.setTopic("order-topic");
        t.setTag("order.updated");
        t.setPayload(body.getBytes(StandardCharsets.UTF_8));
        t.setErrorStage("WRITE");
        t.setErrorCode("PG_CONSTRAINT_VIOLATION");
        t.setErrorMessage("测试构造");
        t.setAttempt(attempt);
        t.setMaxAttempt(maxAttempt);
        t.setNextRetryAt(now);
        t.setStatus("RUNNING");   // 模拟已被调度器领取
        t.setLeaseUntil(now.plusMinutes(5));
        t.setLastErrorAt(now);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        retryTaskMapper.insert(t);
        return t;
    }

    private static String payload(long id, String name, String amount, String status, String updatedAt) {
        return """
                {"id":%d,"name":"%s","amount":%s,"status":"%s","updatedAt":"%s"}"""
                .formatted(id, name, amount, status, updatedAt);
    }

    private String nameOf(long id) {
        List<String> got = jdbc.queryForList(
                "SELECT name FROM biz_demo.orders WHERE id = ?", String.class, id);
        return got.isEmpty() ? null : got.get(0);
    }

    private BigDecimal amountOf(long id) {
        return jdbc.queryForObject(
                "SELECT amount FROM biz_demo.orders WHERE id = ?", BigDecimal.class, id);
    }
}
