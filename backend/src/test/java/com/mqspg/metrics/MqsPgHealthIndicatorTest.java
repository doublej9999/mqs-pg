package com.mqspg.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.mqspg.consumer.PgHealthGate;
import com.mqspg.raw.RawStore;
import com.mqspg.retry.RetryService;

/**
 * 健康指示器测试（里程碑 H2）。
 *
 * <p>纯单元测试、不起 Spring：这里要验证的核心是**它不去查库**，
 * 而「查库」这件事只有用 mock 才能观察得到。
 */
@DisplayName("业务健康指示器")
class MqsPgHealthIndicatorTest {

    private static final Duration STALE_AFTER = Duration.ofSeconds(45);

    private final PgHealthGate gate = mock(PgHealthGate.class);
    private final RetryService retryService = mock(RetryService.class);
    private final RawStore rawStore = mock(RawStore.class);

    private MqsPgHealthIndicator indicator(long backlogThreshold) {
        when(rawStore.writtenCount()).thenReturn(0L);
        when(rawStore.droppedCount()).thenReturn(0L);
        return new MqsPgHealthIndicator(gate, retryService, rawStore,
                backlogThreshold, 0.1, 1000L, STALE_AFTER);
    }

    @Test
    @DisplayName("积压读的是内存缓存，绝不调用会查库的 summary()")
    void usesCachedSummaryNotLiveQuery() {
        when(gate.snapshot()).thenReturn(Map.of(1L, true));
        when(retryService.cachedSummary(STALE_AFTER)).thenReturn(counts(5, 0, false));

        Health health = indicator(10_000).health();

        assertEquals(Status.UP, health.getStatus());
        // 关键断言：K8s 每 5–10s 探一次，若这里落到 summary() 就是一次 GROUP BY
        verify(retryService, never()).summary();
        verify(retryService).cachedSummary(STALE_AFTER);
    }

    @Test
    @DisplayName("积压缓存过期只标注 stale，不判 DOWN")
    void staleBacklogDoesNotMarkDown() {
        when(gate.snapshot()).thenReturn(Map.of(1L, true));
        when(retryService.cachedSummary(STALE_AFTER)).thenReturn(counts(5, 0, true));

        Health health = indicator(10_000).health();

        // 探针要回答的是「进程能否服务」，不是「指标是否新鲜」；
        // 判 DOWN 会让一次指标刷新失败直接导致实例被摘掉
        assertEquals(Status.UP, health.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> backlog = (Map<String, Object>) health.getDetails().get("retryBacklog");
        assertEquals(true, backlog.get("stale"));
    }

    @Test
    @DisplayName("闸门关闭仍是 DOWN，积压超阈值是 OUT_OF_SERVICE")
    void statusMapping() {
        when(gate.snapshot()).thenReturn(Map.of(1L, false));
        when(retryService.cachedSummary(any())).thenReturn(counts(0, 0, false));
        assertEquals(Status.DOWN, indicator(10_000).health().getStatus());

        when(gate.snapshot()).thenReturn(Map.of(1L, true));
        when(retryService.cachedSummary(any())).thenReturn(counts(20_000, 0, false));
        assertEquals(Status.OUT_OF_SERVICE, indicator(10_000).health().getStatus());
    }

    @Test
    @DisplayName("读取积压抛异常也不会让 health 端点崩掉")
    void failuresDoNotThrow() {
        when(gate.snapshot()).thenReturn(Map.of(1L, true));
        when(retryService.cachedSummary(any())).thenThrow(new IllegalStateException("boom"));

        Health health = indicator(10_000).health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
        assertTrue(String.valueOf(health.getDetails().get("retryBacklogError")).contains("积压读取失败"));
    }

    private static Map<String, Object> counts(long pending, long dlq, boolean stale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("PENDING", pending);
        m.put("RUNNING", 0L);
        m.put("DLQ", dlq);
        m.put("stale", stale);
        return m;
    }
}
