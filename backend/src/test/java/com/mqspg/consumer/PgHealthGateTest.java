package com.mqspg.consumer;

import com.mqspg.metrics.MqsMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PG 健康闸门的边界测试（里程碑 H5）。
 *
 * <p>闸门的 key 是 {@code ConcurrentHashMap} 的 key，null 会抛 NPE。
 * 这条 NPE 曾经出现在 {@code RouteConsumer.tryRecover} 的 **catch 块里** ——
 * 它会从 catch 逃出去、被消费循环最外层的 {@code catch (Throwable)} 接住，
 * 结果是消费线程永久退出、只留一条 ERROR 日志：**静默停掉一个路由**。
 * 现在参数类型是原始 {@code long}，null 在编译期就传不进来；这里再钉一遍运行时行为。
 */
@DisplayName("PG 健康闸门边界")
class PgHealthGateTest {

    private PgHealthGate gate() {
        return new PgHealthGate(3, 2, mock(MqsMetrics.class));
    }

    @Test
    @DisplayName("未知数据源（-1）默认放行，且不抛异常")
    void unknownDatasourceIsOpenByDefault() {
        PgHealthGate gate = gate();

        // -1 是 RouteConsumer 在拿不到目标数据源时的约定值。
        // 闸门对它必须「未知默认放行」——否则路由会被永久暂停。
        assertDoesNotThrow(() -> gate.isOpen(-1L));
        assertTrue(gate.isOpen(-1L), "未知数据源应默认放行");

        // 记录失败也不能炸：真实数据源的计数不该被 -1 污染到影响判断
        assertDoesNotThrow(() -> gate.recordFailure(-1L, new RuntimeException("boom")));
        assertDoesNotThrow(() -> gate.recordSuccess(-1L));
        assertDoesNotThrow(() -> gate.lastError(-1L));
    }

    @Test
    @DisplayName("连续失败达阈值关闭闸门，连续成功达阈值重新打开")
    void closesAndReopens() {
        PgHealthGate gate = gate();
        long ds = 7L;

        gate.recordFailure(ds, new RuntimeException("x"));
        gate.recordFailure(ds, new RuntimeException("x"));
        assertTrue(gate.isOpen(ds), "未达阈值前不应关闭");

        gate.recordFailure(ds, new RuntimeException("x"));
        assertFalse(gate.isOpen(ds), "连续 3 次失败后应关闭");

        gate.recordSuccess(ds);
        assertFalse(gate.isOpen(ds), "恢复需要连续 2 次成功");

        gate.recordSuccess(ds);
        assertTrue(gate.isOpen(ds), "连续 2 次成功后应重新打开");
    }
}
