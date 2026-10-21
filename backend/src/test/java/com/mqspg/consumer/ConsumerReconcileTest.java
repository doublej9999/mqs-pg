package com.mqspg.consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.mqspg.support.AbstractPgIntegrationTest;

/**
 * 消费者周期性补齐的契约测试。
 *
 * <p>背景（L2 容器化时发现）：{@code ConsumerManager#startRoute} 在
 * 「目标数据源不可用」时不提交消费者线程、只把状态置 PAUSED 就返回。
 * 原注释说「交给 H1 注册表对账去重新拉起」，但 H1 的 reconcile 只在内存版本与
 * 库内版本不一致时刷新注册表，从不调 ConsumerManager；而
 * {@code RouteConsumer#tryRecover} 是消费者线程内部的逻辑 —— 线程根本没建，
 * 没人去调。结果是这类路由**永久卡在 PAUSED**。
 * 容器编排下这几乎是必然：后端可能先于数据库/网络就绪。
 *
 * <p>修法是加一个周期性「补差」任务。这里只钉两件事：
 * <ol>
 *   <li>它在 {@code auto-start=false}（测试/离线模式）下**什么都不做** ——
 *       否则后台线程会冒出来跟断言抢数据，这也是整个测试基类设这个开关的原因；</li>
 *   <li>调用它不会抛异常（库暂时不可用时它必须自己吞掉，否则定时任务会挂）。</li>
 * </ol>
 */
@TestPropertySource(properties = {
        // 关键：显式关闭自动启动，验证补差任务尊重这个开关
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        "mqs-pg.registry.reconcile-enabled=false"
})
@DisplayName("消费者周期性补齐")
class ConsumerReconcileTest extends AbstractPgIntegrationTest {

    @Autowired
    private ConsumerManager consumerManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("auto-start=false 时补差任务不拉起任何消费者")
    void reconcileDoesNothingWhenAutoStartDisabled() {
        // 不抛异常
        assertDoesNotThrow(() -> consumerManager.reconcileConsumers());

        // 且确实没有提交消费者：demo 种子里的路由 1 是 ACTIVE 的，
        // 若开关被忽略，这里会多出一个运行中的消费线程。
        assertDoesNotThrow(() -> consumerManager.reconcileConsumers());
    }

    /**
     * 补差任务的核心价值：把「曾经因为目标库不可用而停在 PAUSED」的路由重新拉起来。
     *
     * <p>这条用例跑在**真实 PG** 上：先把路由状态手工改成 PAUSED（模拟首次探测
     * 失败留下的残局），再调补差任务，断言状态回到 RUNNING。
     * 修复前这条会失败 —— 因为根本没有代码会在事后重试 startRoute。
     */
    @Test
    @DisplayName("PAUSED 的路由会被补差任务重新拉起")
    void reconcileRevivesPausedRoute() {
        // 模拟「首次探测失败」的残局：库里的状态是 PAUSED，且没有消费者线程
        jdbc.update("UPDATE mqs_pg.rt_consumer_state SET status='PAUSED', reason=? WHERE route_id=1",
                "目标数据源不可用，等待恢复后自动拉起");

        // 这里必须临时越过 auto-start 开关：本测试基类为了不干扰断言把它关了，
        // 但被测的正是「开着的时候会补差」这个行为。
        consumerManager.setAutoStartForTest(true);
        try {
            consumerManager.reconcileConsumers();

            // 断言要放在 finally 之前：stop() 本身会把状态写回 PAUSED
            // （见 ConsumerManager#stop），放在 finally 之后就永远读不到 RUNNING。
            assertEquals("RUNNING", jdbc.queryForObject(
                    "SELECT status FROM mqs_pg.rt_consumer_state WHERE route_id=1", String.class));
        } finally {
            consumerManager.setAutoStartForTest(false);
            // 收尾：停掉刚拉起的消费者，避免它在本类的后续用例里继续抢消息
            consumerManager.stop(1, "测试收尾");
        }
    }
}
