package com.mqspg.support;

import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.junit.jupiter.api.BeforeEach;

/**
 * 集成测试基类（里程碑 D1）：用 Testcontainers 起一个真实的 PostgreSQL，
 * 替代"先在本机 docker 起 PG、再灌 dev 种子"的手工前置条件。
 *
 * <p><b>为什么是单例容器</b>：容器在静态初始化块里启动，整个测试 JVM 共享一个实例；
 * 若用 {@code @Container}（每个测试类启停一次）会在多测试类下反复拉起容器，慢得离谱。
 * 容器由 Testcontainers 的 Ryuk 在 JVM 退出时回收。
 *
 * <p><b>镜像必须是 17+</b>：`MERGE ... RETURNING merge_action()` 是 PG 15 引入、
 * 但本项目实测要求 17+（见 README）；这里固定 {@code postgres:18}。
 *
 * <p><b>密码为什么是 123456</b>：dev 种子把目标数据源密码写死为 {@code {noop}123456}，
 * 且 {@code ConfigAdminTest} 也硬编码该密码。容器沿用同一密码，
 * 这样只需要把种子里的 **URL** 指到容器，密码无需改动。
 *
 * <p><b>目标数据源</b>：应用配置库与业务目标库在本项目里是两个数据源。
 * 配置库由 {@code @DynamicPropertySource} 指向容器；业务目标库则来自
 * {@code cfg_datasource} 种子行，因此子类测试前会把它改写成容器 URL。
 */
@SpringBootTest
@ActiveProfiles("dev")
public abstract class AbstractPgIntegrationTest {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18")
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("123456");

    /**
     * 不带查询参数的容器 URL —— Testcontainers 会在 {@code getJdbcUrl()} 后面挂
     * {@code ?loggerLevel=OFF}，直接拼 {@code ?currentSchema=...} 会拼出两个 {@code ?}，
     * 整个查询串失效，MyBatis 的无限定 SQL 就会落到 public 并报
     * {@code relation "cfg_route" does not exist}。这里先把查询串剥掉。
     */
    private static final String BASE_JDBC_URL;

    static {
        // 必须在 @DynamicPropertySource 求值前完成启动
        POSTGRES.start();
        String url = POSTGRES.getJdbcUrl();
        int q = url.indexOf('?');
        BASE_JDBC_URL = q < 0 ? url : url.substring(0, q);
    }

    /** 配置库连接：走容器，并显式带上 currentSchema（否则 MyBatis 的无限定 SQL 落 public）。 */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> BASE_JDBC_URL + "?currentSchema=mqs_pg,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TargetDataSourceRegistry dataSources;

    /**
     * 把 dev 种子里的目标数据源指向容器。
     *
     * <p>刻意**不**调用 {@code dataSources.invalidate}：容器 URL 在整个 JVM 内不变，
     * 且没有任何 bean 会在启动时预先建池，因此第一次 {@code jdbc(1)} 之前改写即可生效；
     * 若在此处 invalidate，反而可能关掉上一个测试仍在使用的连接池。
     */
    @BeforeEach
    void alignSeedDatasourceWithContainer() {
        jdbc.update("UPDATE mqs_pg.cfg_datasource SET jdbc_url = ? WHERE id = 1", containerJdbcUrl());
    }

    /** 容器的 JDBC URL（无查询参数、无 currentSchema；目标表在 SQL 里是限定名）。 */
    protected static String containerJdbcUrl() {
        return BASE_JDBC_URL;
    }

    protected static String containerPassword() {
        return POSTGRES.getPassword();
    }
}
