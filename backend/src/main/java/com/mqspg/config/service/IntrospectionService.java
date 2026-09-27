package com.mqspg.config.service;

import com.mqspg.common.crypto.PasswordCodec;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.config.dto.ColumnDto;
import com.mqspg.config.dto.ConnectionTestDto;
import com.mqspg.config.dto.DatasourceRequest;
import com.mqspg.config.entity.CfgDatasource;
import com.mqspg.config.mapper.CfgDatasourceMapper;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * 目标库结构探查：给页面的 schema / 表 / 列下拉框提供数据。
 *
 * <p>存在的意义是**避免手敲标识符**。「写到某个 schema 的某张表」如果用文本框输入，
 * 拼错表名不会立刻报错，要等到消息落库失败才暴露 —— 而那时坏消息已经进了重试表。
 * 让用户在真实存在的对象里挑，把这类错误提前到配置阶段。
 *
 * <p>所有查询都限定在**已保存的数据源**或请求里显式给出的连接参数上，
 * 不缓存结果：DDL 随时可能变，下拉框慢一点无所谓，给错列表更糟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntrospectionService {

    /** 排除系统 schema：页面选的应当是可写的业务对象。 */
    private static final String SCHEMAS_SQL = """
            SELECT nspname
            FROM pg_namespace
            WHERE nspname NOT LIKE 'pg\\_%'
              AND nspname <> 'information_schema'
            ORDER BY nspname
            """;

    /** 只列普通表与分区表：视图/外部表无法用 MERGE 稳定写入。 */
    private static final String TABLES_SQL = """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN ('r', 'p')
            ORDER BY c.relname
            """;

    private static final String COLUMNS_SQL = """
            SELECT a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   a.atthasdef AS has_default,
                   COALESCE(pk.is_pk, false) AS is_pk,
                   COALESCE(uq.is_uq, false) AS is_uq
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN LATERAL (
                SELECT true AS is_pk
                FROM pg_index i
                WHERE i.indrelid = c.oid AND i.indisprimary AND a.attnum = ANY(i.indkey)
                LIMIT 1
            ) pk ON true
            LEFT JOIN LATERAL (
                SELECT true AS is_uq
                FROM pg_index i
                WHERE i.indrelid = c.oid AND i.indisunique AND a.attnum = ANY(i.indkey)
                LIMIT 1
            ) uq ON true
            WHERE n.nspname = ? AND c.relname = ?
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

    private final CfgDatasourceMapper datasourceMapper;
    private final TargetDataSourceRegistry dataSources;
    private final PasswordCodec passwordCodec;

    // ------------------------------------------------------------------
    // 结构探查
    // ------------------------------------------------------------------

    public List<String> listSchemas(long datasourceId) {
        return jdbc(datasourceId).queryForList(SCHEMAS_SQL, String.class);
    }

    public List<String> listTables(long datasourceId, String schema) {
        requireIdentifying(schema, "schema");
        return jdbc(datasourceId).queryForList(TABLES_SQL, String.class, schema);
    }

    public List<ColumnDto> listColumns(long datasourceId, String schema, String table) {
        requireIdentifying(schema, "schema");
        requireIdentifying(table, "表名");
        return jdbc(datasourceId).query(COLUMNS_SQL, (rs, i) -> {
            String pgType = rs.getString("data_type");
            boolean nullable = rs.getBoolean("nullable");
            boolean hasDefault = rs.getBoolean("has_default");
            return new ColumnDto(
                    rs.getString("column_name"),
                    pgType,
                    nullable,
                    hasDefault,
                    rs.getBoolean("is_pk"),
                    rs.getBoolean("is_uq"),
                    !nullable && !hasDefault,
                    suggestTransform(pgType));
        }, schema, table);
    }

    // ------------------------------------------------------------------
    // 试连
    // ------------------------------------------------------------------

    /** 用已保存的数据源试连（页面点「测试连接」）。 */
    public ConnectionTestDto testStored(long datasourceId) {
        CfgDatasource cfg = datasourceMapper.selectById(datasourceId);
        if (cfg == null) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "数据源不存在: " + datasourceId);
        }
        return probe(cfg.getJdbcUrl(), cfg.getUsername(), passwordCodec.decode(cfg.getPasswordEnc()));
    }

    /**
     * 用请求里的参数试连 —— 用于**保存之前**先验证。
     *
     * <p>密码留空时回落到已保存的密码，这样编辑一个已有数据源时
     * 不必把密码重新输一遍（页面本来就不回显密码）。
     */
    public ConnectionTestDto testCandidate(Long datasourceId, DatasourceRequest req) {
        if (req == null || req.jdbcUrl() == null || req.jdbcUrl().isBlank()) {
            return ConnectionTestDto.failure("JDBC URL 不能为空");
        }
        String password = req.password();
        if ((password == null || password.isBlank()) && datasourceId != null) {
            CfgDatasource stored = datasourceMapper.selectById(datasourceId);
            if (stored != null) {
                password = passwordCodec.decode(stored.getPasswordEnc());
            }
        }
        return probe(req.jdbcUrl(), req.username(), password);
    }

    private ConnectionTestDto probe(String jdbcUrl, String username, String password) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("mqspg-probe");
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(username);
        hc.setPassword(password);
        // 试连只借一条连接，拿到元数据就还；池子用完即关
        hc.setMaximumPoolSize(1);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(5_000);
        hc.setInitializationFailTimeout(-1);

        try (HikariDataSource ds = new HikariDataSource(hc);
             Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            String version = md.getDatabaseProductVersion();
            // 目标库必须是 PostgreSQL：MERGE ... RETURNING 与分区语法都是 PG 专有
            if (product == null || !product.toLowerCase().contains("postgresql")) {
                return ConnectionTestDto.failure("目标库不是 PostgreSQL，实际为: " + product);
            }
            return ConnectionTestDto.success(product, version);
        } catch (SQLException e) {
            log.warn("数据源试连失败: url={} 原因={}", jdbcUrl, e.getMessage());
            return ConnectionTestDto.failure(rootMessage(e));
        } catch (RuntimeException e) {
            return ConnectionTestDto.failure(rootMessage(e));
        }
    }

    // ------------------------------------------------------------------

    private JdbcTemplate jdbc(long datasourceId) {
        return dataSources.jdbc(datasourceId);
    }

    /**
     * 拒绝空值与带引号的标识符。
     *
     * <p>这里用的是**参数绑定**，本无注入风险；但仍然拒绝引号，
     * 因为目标表名会原样进入 identifier 位置（"schema"."table"），
     * 带引号的名字在那里会被拼成非法 SQL。
     */
    private static void requireIdentifying(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, what + "不能为空");
        }
        if (value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) {
            throw new ProcessingException(ErrorCode.CONFIG_MISSING,
                    what + "不能包含引号: " + value);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /** 依 PG 类型推荐转换类型，页面据此预填映射行。 */
    static String suggestTransform(String pgType) {
        if (pgType == null) {
            return "string";
        }
        String t = pgType.toLowerCase();
        if (t.startsWith("timestamp") || t.equals("date")) {
            return "timestamp";
        }
        if (t.equals("boolean") || t.equals("bool")) {
            return "boolean";
        }
        if (t.equals("bigint") || t.equals("int8")) {
            return "long";
        }
        if (t.equals("integer") || t.equals("int") || t.equals("int4")
                || t.equals("smallint") || t.equals("int2")) {
            return "integer";
        }
        if (t.startsWith("numeric") || t.startsWith("decimal")
                || t.startsWith("real") || t.startsWith("double")) {
            return "decimal";
        }
        return "string";
    }
}
