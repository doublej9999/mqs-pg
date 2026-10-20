package com.mqspg.config;

import com.mqspg.support.AbstractPgIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway {@code baseline-on-migrate} 的行为测试（里程碑 I8）。
 *
 * <p><b>要证明的是</b>：{@code baseline-on-migrate: true} 在「有表但没有
 * flyway_schema_history」的库上会**静默跳过** V1 —— 不报错，启动照样成功，
 * 表缺失要等第一次查询才暴露。这与 AGENTS.md 记的「Flyway 静默不建表」
 * 是同一类失败，只在生产上发生（dev 是空库，走的是正常迁移路径）。
 *
 * <p>置 {@code false} 后变成启动阶段直接失败 —— 故障暴露在最前面，
 * 这是 I8 要的效果。用例同时验证两种配置下的相反结果，
 * 避免「以为改对了其实没生效」。
 */
@DisplayName("Flyway baseline 行为（I8）")
class FlywayBaselineTest extends AbstractPgIntegrationTest {

    /** 专门开一个 schema，不去碰主测试库的 mqs_pg。 */
    private static final String SCHEMA = "mqs_pg_i8";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("有表无 history：baseline=true 静默跳过，baseline=false 启动即失败")
    void baselineOnMigrateSkipsV1Silently() {
        try {
            // 造一个「已有表但没有 flyway_schema_history」的库：
            // 这正是从别的系统接管、或从手工建表的库迁移过来时的样子
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            jdbc.execute("CREATE SCHEMA " + SCHEMA);
            jdbc.execute("CREATE TABLE " + SCHEMA + ".legacy_table (id bigint)");

            // ---- ① baseline-on-migrate = true + 默认 baseline-version：静默跳过 V1 ----
            //
            // 关键在 baseline-version。Flyway 会把「当前状态」记成该版本号，
            // 然后**只执行比它新的迁移**。默认值是 1 —— 于是 V1 被当成「已经做过」跳过。
            // 主配置 application.yml 没设 baseline-version，走的正是这个默认值，
            // 所以这里也刻意不设，才能复现生产上的那个坑。
            Flyway lenient = flyway(true, null);
            try {
                lenient.migrate();
            } catch (FlywayException e) {
                // V2 引用了 V1 建的表，V1 被跳过时 V2 必然失败。
                // 这比「静默跳过」更直白地暴露了问题 —— 说明 V1 确实没执行。
                // 但请注意：真实的生产库里如果只有 V1（或 V2 恰好没引用 V1 的表），
                // 这一步会**成功返回**，故障就完全静默了。所以下面还要正面断言。
                assertTrue(e.getMessage() == null || !e.getMessage().contains("V1__"),
                        () -> "失败的应是依赖 V1 的 V2，而不是 V1 本身: " + e.getMessage());
            }

            // 无论 migrate() 是成功还是因为 V2 失败，结论都一样：
            // V1 没有执行，cfg_route 不存在。这就是静默失败的证据 ——
            // 启动可能不报错，业务在第一次查询时才炸。
            Integer created = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? AND table_name = 'cfg_route'",
                    Integer.class, SCHEMA);
            assertTrue(created == null || created == 0,
                    "baseline=true（默认 baseline-version=1）时 V1 应被跳过（cfg_route 不该存在）");

            // ---- ② baseline-on-migrate = false：启动阶段直接失败 ----
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            jdbc.execute("CREATE SCHEMA " + SCHEMA);
            jdbc.execute("CREATE TABLE " + SCHEMA + ".legacy_table (id bigint)");

            Flyway strict = flyway(false, null);
            FlywayException e = assertThrows(FlywayException.class, strict::migrate,
                    "baseline=false 时对非空且无 history 的库必须直接失败");
            // 提示要能看懂：Flyway 会说要 baseline 或手工处理
            assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    /**
     * 显式 {@code baseline-version: 0} 时 V1 **会**执行 —— 这是
     * {@code application-prod.yml.example} 采用的值，效果比默认值 1 更保守：
     * 即便有人误开了 baseline，也不会丢掉 V1 建表。
     */
    @Test
    @DisplayName("baseline-version=0 时即便开了 baseline，V1 仍会执行")
    void baselineVersionZeroStillRunsV1() {
        try {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            jdbc.execute("CREATE SCHEMA " + SCHEMA);
            jdbc.execute("CREATE TABLE " + SCHEMA + ".legacy_table (id bigint)");

            flyway(true, "0").migrate();

            Integer created = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? AND table_name = 'cfg_route'",
                    Integer.class, SCHEMA);
            assertEquals(1, created, "baseline-version=0 时 V1 应正常执行");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    /** @param baselineVersion {@code null} 表示不设置，用 Flyway 默认值 1 */
    private Flyway flyway(boolean baselineOnMigrate, String baselineVersion) {
        var cfg = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate);
        if (baselineVersion != null) {
            cfg = cfg.baselineVersion(baselineVersion);
        }
        return cfg.load();
    }
}
