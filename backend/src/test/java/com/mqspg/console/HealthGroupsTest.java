package com.mqspg.console;

import com.mqspg.support.AbstractPgIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 健康分组测试（里程碑 H7）。
 *
 * <p>关键在于**liveness 与 readiness 判据不同**：只有一个
 * {@code /actuator/health} 时，K8s 拿它当 liveness，一次 PG 抖动就会让整个 Pod
 * 被重启 —— 而重启修不好 PG，反而丢掉内存缓冲、放大故障。
 */
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        "mqs-pg.raw.enabled=false"
})
@DisplayName("健康检查分组")
class HealthGroupsTest extends AbstractPgIntegrationTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    @DisplayName("liveness 只看进程存活，不包含数据库")
    void livenessExcludesDatabase() {
        var liveness = healthEndpoint.healthForPath("liveness");

        assertNotNull(liveness, "liveness 分组应存在");
        assertEquals(Status.UP, liveness.getStatus());
        // ping / diskSpace 都在；db 与业务指示器**刻意不在** ——
        // 它们在就是「PG 一抖重启整个 Pod」
        String components = String.valueOf(((CompositeHealthDescriptor) liveness).getComponents().keySet());
        assertEquals(true, components.contains("ping"), () -> "liveness 应包含 ping: " + components);
        assertEquals(false, components.contains("mqsPg"),
                () -> "liveness 不得包含业务指示器，否则 PG 抖动会重启 Pod: " + components);
    }

    @Test
    @DisplayName("readiness 包含数据库与业务指示器")
    void readinessIncludesDatabase() {
        var readiness = healthEndpoint.healthForPath("readiness");

        assertNotNull(readiness, "readiness 分组应存在");
        String components = String.valueOf(((CompositeHealthDescriptor) readiness).getComponents().keySet());
        assertEquals(true, components.contains("db"), () -> "readiness 应包含 db: " + components);
        assertEquals(true, components.contains("mqsPg"), () -> "readiness 应包含 mqsPg: " + components);
    }
}
