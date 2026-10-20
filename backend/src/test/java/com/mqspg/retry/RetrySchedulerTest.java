package com.mqspg.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import com.mqspg.support.AbstractPgIntegrationTest;

/**
 * 重试调度器测试（阶段 6）。
 *
 * <p>调度轮询被推到 24 小时，测试直接调用 {@code processOne}，
 * 断言完全确定，不受后台线程影响。
 */
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h"
})
@DisplayName("重试调度器")
class RetrySchedulerTest extends AbstractPgIntegrationTest {

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
    @DisplayName("绑定版本不存在 → 前几轮只缓冲、不消耗次数")
    void missingConfigDoesNotConsumeAttempt() {
        long id = 980063L;
        // 版本 999 不存在。若实现「回退到 ACTIVE 版本」，这次重放就会成功 ——
        // 因此本用例同时证明了重放严格使用绑定版本（PRD §19）。
        RtRetryTask task = insert("rtt-4", payload(id, "ghost", "1.00", "PAID",
                "2026-04-07T10:00:00+08:00"), 3, 10, 999);

        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("PENDING", after.getStatus(), "缓冲期内仍应待重试");
        assertEquals(3, after.getAttempt(), "配置缺失不得消耗重试次数");
        assertEquals("CONFIG_GONE", after.getErrorCode());
        assertTrue(after.getErrorMessage().startsWith("[configGone=1]"),
                "应记下缓冲轮次，实际: " + after.getErrorMessage());
        assertNotNull(after.getNextRetryAt());
        assertNull(nameOf(id), "绑定版本不可用时绝不能落库");
    }

    @Test
    @DisplayName("绑定版本不存在 → 缓冲轮数用尽后进 DLQ，不再无限重试")
    void missingConfigEventuallyGoesToDlq() {
        long id = 980069L;
        // 版本 999 永远不存在：这正是修复前的「断头路」——
        // 每轮都「暂时失败」、next_retry_at 只往后推，任务永不进 DLQ，表只增不减。
        RtRetryTask task = insert("rtt-gone", payload(id, "gone", "1.00", "PAID",
                "2026-04-12T10:00:00+08:00"), 1, 10, 999);

        // 缓冲上限默认 3 轮：第 1..3 轮留在 PENDING，第 4 轮进 DLQ
        for (int round = 1; round <= 3; round++) {
            scheduler.processOne(task);
            RtRetryTask mid = retryTaskMapper.selectById(task.getId());
            assertEquals("PENDING", mid.getStatus(), "第 %d 轮应仍在缓冲".formatted(round));
            task = mid;
        }
        scheduler.processOne(task);

        RtRetryTask after = retryTaskMapper.selectById(task.getId());
        assertEquals("DLQ", after.getStatus(), "缓冲耗尽后必须进 DLQ，而不是永远 PENDING");
        assertEquals(1, after.getAttempt(), "永久缺失不得消耗重试次数");

        Integer finals = jdbc.queryForObject(
                "SELECT count(*) FROM mqs_pg.rt_error_record WHERE message_id = 'rtt-gone' AND is_final = true",
                Integer.class);
        assertEquals(1, finals, "进 DLQ 必须留下可查询的终态流水");
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

    /**
     * J2：续租只认 {@code status='RUNNING'}。
     *
     * <p>领取时整批打同一个租约，处理是逐条的 —— 已处理完的已经是
     * SUCCEEDED / PENDING。若续租不带状态条件，会给这些行重新写回租约，
     * 让它们看起来仍在处理中。
     */
    @Test
    @DisplayName("续租只作用于 RUNNING，已完成的任务不会被复活")
    void renewLeaseOnlyTouchesRunning() {
        RtRetryTask running = insert("rtt-renew-1", "{}", 0, 10, 1);
        RtRetryTask done = insert("rtt-renew-2", "{}", 0, 10, 1);
        jdbc.update("UPDATE mqs_pg.rt_retry_task SET status = 'SUCCEEDED', lease_until = NULL WHERE id = ?",
                done.getId());
        jdbc.update("UPDATE mqs_pg.rt_retry_task SET lease_until = now() - interval '1 hour' WHERE id = ?",
                running.getId());

        int renewed = retryTaskMapper.renewLease(
                List.of(running.getId(), done.getId()), 600L);

        assertEquals(1, renewed, "只有 RUNNING 那一行应被续租");
        // 受影响行数和传入 id 数不符，调用方据此判定「有人抢走了」并停止本轮
        OffsetDateTime lease = jdbc.queryForObject(
                "SELECT lease_until FROM mqs_pg.rt_retry_task WHERE id = ?",
                OffsetDateTime.class, running.getId());
        assertTrue(lease.isAfter(OffsetDateTime.now().plusMinutes(5)),
                "租约应被推到约 10 分钟后，实际 " + lease);
    }

    /**
     * J1：同版本的多条任务（含同一 Upsert Key 的重复行）走批量路径时，
     * 折叠成**一次**写入，且每条任务的 attempt 账本彼此独立。
     *
     * <p>用 {@code poll()} 而不是直接调私有方法，是为了让用例走真实路径：
     * 领取 → 分组 → 折叠 → 一次 write → 逐条回写。
     */
    @Test
    @DisplayName("批量重放：同版本任务折叠成一次写入，attempt 各自独立")
    void batchReplayFoldsWithinSameVersion() {
        long id = 980070L;
        // 三条任务指向同一个 Upsert Key，update_time 递增：最新那条应胜出
        RtRetryTask older = pending("rtt-b1", payload(id, "older", "1.00", "PAID",
                "2026-05-01T10:00:00+08:00"), 2, 10);
        RtRetryTask newest = pending("rtt-b2", payload(id, "newest", "2.00", "PAID",
                "2026-05-01T12:00:00+08:00"), 0, 10);
        // 另起一个 Key，验证组内是多行不是单行
        RtRetryTask other = pending("rtt-b3", payload(id + 1, "other", "3.00", "PAID",
                "2026-05-01T11:00:00+08:00"), 1, 10);

        scheduler.poll();

        // 折叠生效：更新的记录胜出
        assertEquals("newest", nameOf(id));
        assertEquals(0, new BigDecimal("2.00").compareTo(amountOf(id)));
        assertEquals("other", nameOf(id + 1));

        // 三条都成功（折叠不等于丢弃：更旧的那条也应有明确终态）
        for (RtRetryTask t : List.of(older, newest, other)) {
            RtRetryTask after = retryTaskMapper.selectById(t.getId());
            assertEquals("SUCCEEDED", after.getStatus(),
                    () -> "任务 " + t.getMessageId() + " 应为 SUCCEEDED，实际 " + after.getStatus());
            // attempt 不被批量路径重置：账本是逐条的
            assertEquals(t.getAttempt(), after.getAttempt(),
                    () -> "任务 " + t.getMessageId() + " 的 attempt 不该被改动");
        }
    }

    /**
     * J1：组内一条任务转换失败，不应拖累同组其它任务。
     *
     * <p>失败那条拿到自己的 DATA_ERROR 并消耗次数，其余照常写入并成功。
     */
    @Test
    @DisplayName("批量重放：组内单条数据错误只影响它自己")
    void batchReplayIsolatesDataErrorWithinGroup() {
        long okId = 980072L;
        RtRetryTask good = pending("rtt-c1", payload(okId, "good", "5.00", "PAID",
                "2026-05-02T10:00:00+08:00"), 0, 10);
        // 金额溢出 NUMERIC(18,2)：transform 阶段就会失败
        RtRetryTask bad = pending("rtt-c2", payload(okId + 1, "bad", "99999999999999999999", "PAID",
                "2026-05-02T10:00:00+08:00"), 0, 10);

        scheduler.poll();

        assertEquals("SUCCEEDED", retryTaskMapper.selectById(good.getId()).getStatus());
        assertEquals("good", nameOf(okId));

        RtRetryTask badAfter = retryTaskMapper.selectById(bad.getId());
        assertEquals("PENDING", badAfter.getStatus(), "数据错误应重新排队（未耗尽次数）");
        assertEquals(1, badAfter.getAttempt(), "数据错误应消耗一次机会");
        assertNull(nameOf(okId + 1), "坏数据不该落库");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 造一条「已到期、可被 poll() 领取」的 PENDING 任务。 */
    private RtRetryTask pending(String messageId, String body, int attempt, int maxAttempt) {
        RtRetryTask t = insert(messageId, body, attempt, maxAttempt, 1);
        t.setStatus("PENDING");
        t.setLeaseUntil(null);
        t.setNextRetryAt(OffsetDateTime.now().minusSeconds(5));
        retryTaskMapper.updateById(t);
        return t;
    }

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
