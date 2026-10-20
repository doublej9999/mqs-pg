package com.mqspg.config;

import com.mqspg.config.registry.ConfigRegistry;
import com.mqspg.config.service.ConfigAdminService;
import com.mqspg.config.service.ConfigService;
import com.mqspg.config.dto.DatasourceDto;
import com.mqspg.config.dto.DraftDto;
import com.mqspg.config.dto.DatasourceRequest;
import com.mqspg.config.dto.RouteRequest;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.TargetDto;
import com.mqspg.config.dto.TargetRequest;
import com.mqspg.support.AbstractPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 配置注册表的生命周期测试（里程碑 G7）。
 *
 * <p>注册表是「内存认为的配置」与「库里的配置」之间的那一层，
 * 它不同步时症状都很隐蔽：路由已停用却仍被当成生效路由、
 * 版本已删除却仍能重放成功。这里直接断言注册表的返回值。
 */
@TestPropertySource(properties = {
        "mqs-pg.consumer.auto-start=false",
        "mqs-pg.retry.poll-interval=24h",
        "mqs-pg.raw.enabled=false"
})
@DisplayName("配置注册表生命周期")
class ConfigRegistryTest extends AbstractPgIntegrationTest {

    private static final String PREFIX = "it-reg-";
    private static final String SCHEMA = "biz_demo";
    private static final String TABLE = "orders";

    @Autowired
    private ConfigAdminService admin;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ConfigRegistry registry;
    /** 对账是 {@code DbConfigRegistry} 的实现细节，不在接口上，所以这里按具体类型再注一份。 */
    @Autowired
    private com.mqspg.config.registry.DbConfigRegistry dbRegistry;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM cfg_route WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM cfg_target WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM cfg_datasource WHERE name LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("删除非生效版本后，该版本的内存快照被驱逐")
    void deletingVersionEvictsSnapshot() {
        RouteSummaryDto route = createRouteWithTwoVersions();
        int v1 = jdbc.queryForObject(
                "SELECT min(version) FROM cfg_version WHERE route_id = ?", Integer.class, route.id());

        // 先确认快照真的进了注册表（否则下面的断言测的是「本来就没有」）
        assertNotNull(registry.get(route.id(), v1), "激活后该版本应已缓存");

        admin.deleteVersion(route.id(), v1, true);

        assertNull(registry.get(route.id(), v1),
                "版本删除后注册表必须驱逐快照，否则重放会时而有配置时而没有");
    }

    @Test
    @DisplayName("路由被停用后，注册表不再把它当作生效路由")
    void deactivatedRouteDropsOutOfRegistry() {
        RouteSummaryDto route = createRouteWithTwoVersions();
        assertNotNull(registry.active(route.id()), "激活后应有 ACTIVE 快照");
        assertEquals(1, registry.activeRouteIds().stream()
                .filter(id -> id == route.id()).count());

        // 直接把状态改掉（模拟「停用」这个动作），再让注册表刷新 ——
        // 这正是 G7 的场景：refresh(routeId) 由事件驱动，refreshAll 才会过滤状态
        jdbc.update("UPDATE cfg_route SET status = 'INACTIVE' WHERE id = ?", route.id());
        registry.refresh(route.id());

        assertNull(registry.active(route.id()),
                "路由状态不是 ACTIVE 时，注册表不得继续持有它的快照");
        assertEquals(0, registry.activeRouteIds().stream()
                .filter(id -> id == route.id()).count(),
                "已停用的路由不应出现在生效路由列表里");
    }

    @Test
    @DisplayName("对账能把事件丢失造成的漂移拉回来")
    void reconcileRepairsDrift() {
        RouteSummaryDto route = createRouteWithTwoVersions();
        int before = registry.activeVersion(route.id());

        // 直接改库、不发事件 —— 模拟「AFTER_COMMIT 监听器处理失败」或事件丢失。
        // 上一版之后，运行期没有任何周期性刷新，这种漂移会一直持续到重启。
        Integer other = jdbc.queryForObject(
                "SELECT min(version) FROM cfg_version WHERE route_id = ? AND version <> ?",
                Integer.class, route.id(), before);
        assertNotNull(other);
        jdbc.update("UPDATE cfg_route SET active_version = ? WHERE id = ?", other, route.id());
        assertEquals(before, registry.activeVersion(route.id()),
                "不发事件时注册表本来就该是旧值，否则这个用例测不到东西");

        dbRegistry.reconcile();

        assertEquals(other, registry.activeVersion(route.id()),
                "对账后注册表应跟上库里的 active_version");
    }

    // ------------------------------------------------------------------

    /** 建一条有两个版本、且 v1 已生效的路由。 */
    private RouteSummaryDto createRouteWithTwoVersions() {
        DatasourceDto ds = admin.createDatasource(new DatasourceRequest(
                PREFIX + "ds", containerJdbcUrl(), "postgres", "123456", null));
        TargetDto target = admin.createTarget(new TargetRequest(
                PREFIX + "tgt", ds.id(), SCHEMA, TABLE, List.of("id"), "update_time"));
        RouteSummaryDto route = admin.createRoute(
                new RouteRequest(PREFIX + "route", PREFIX + "topic", PREFIX + "tag", target.id()));

        DraftDto v1 = admin.autoGenerateMappings(route.id());
        admin.publishVersion(route.id(), v1.version());
        configService.activate(route.id(), v1.version());

        int v2 = admin.createVersion(route.id()).version();
        admin.publishVersion(route.id(), v2);
        configService.activate(route.id(), v2);

        // 激活 v2 之后 v1 变成 INACTIVE，正是「可以删的非生效版本」
        return route;
    }
}
