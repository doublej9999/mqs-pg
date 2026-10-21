package com.mqspg.writer.datasource;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.crypto.PasswordCodec;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.config.entity.CfgDatasource;
import com.mqspg.config.mapper.CfgDatasourceMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 多目标数据源连接池注册表（tech-design §9.5）。
 *
 * <p>配置库与业务目标库是**两个不同的数据源**：配置库由 Spring 管理，
 * 目标库按 {@code cfg_datasource} 动态建池，生命周期由本类管理。
 *
 * <p><b>事务边界</b>：目标库写入走 JDBC 自动提交 —— 一条 MERGE 语句本身即一个原子事务。
 * 不引入跨库分布式事务：配置库与目标库的一致性通过「重试表先落库再 ACK」保证（ADR-02），
 * 而不是通过 2PC。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetDataSourceRegistry implements DisposableBean {

    private final CfgDatasourceMapper datasourceMapper;
    private final PasswordCodec passwordCodec;

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<Long, JdbcTemplate> templates = new ConcurrentHashMap<>();
    private final Object poolLock = new Object();

    /**
     * 容器化场景的 localhost 纠正（可选）。
     *
     * <p><b>为什么需要</b>：demo 种子数据里的目标数据源写的是
     * {@code jdbc:postgresql://localhost:5432/postgres} —— 在本机直跑时正确，
     * 但把这个后端放进容器后，{@code localhost} 指的是**容器自己**，
     * 于是连接必然被拒（{@code Connection to localhost:5432 refused}）。
     * 表现是路由状态一直是「目标数据源不可用，等待恢复后自动拉起」而永远不恢复。
     *
     * <p>不改种子迁移的原因是它是**已应用**的（见 AGENTS.md：不要编辑已应用的迁移），
     * 而且「本机直跑」这个场景仍然需要 localhost。所以做成运行期覆盖：
     * 配置了本项时，建池前把 URL 里的 localhost / 127.0.0.1 换成指定主机。
     * docker-compose 里设为 {@code postgres}（服务名）。
     *
     * <p>只替换**主机名**，不动端口、库名与参数；未配置时原文使用，行为不变。
     */
    @Value("${mqs-pg.datasource.localhost-override:}")
    private String localhostOverride;

    /**
     * 把 URL 里的 localhost / 127.0.0.1 换成配置的主机。
     *
     * <p>写成静态方法便于单测直接覆盖，不必起 Spring 上下文。
     *
     * @param url    原始 jdbc url，可为 null
     * @param target 目标主机；为空表示不替换
     * @return 替换后的 url（无法识别时不改动原文）
     */
    static String rewriteLocalhost(String url, String target) {
        if (url == null || target == null || target.isBlank()) {
            return url;
        }
        // 只在「主机位」上替换，避免误伤库名/参数里出现的同名字串
        // 形如 jdbc:postgresql://localhost:5432/db?x=localhost
        return url.replaceFirst("(jdbc:postgresql://)(localhost|127\\.0\\.0\\.1)([:/?])",
                "$1" + java.util.regex.Matcher.quoteReplacement(target) + "$3");
    }

    public JdbcTemplate jdbc(Long datasourceId) {
        JdbcTemplate cached = templates.get(datasourceId);
        if (cached != null) {
            return cached;
        }
        // 双重检查 + 专用锁：建池要做 TCP 握手，既不能放在 ConcurrentHashMap 的桶锁里，
        // 也不能用 putIfAbsent 竞态 —— 失败的一方会泄漏一个已建好的 HikariDataSource。
        synchronized (poolLock) {
            cached = templates.get(datasourceId);
            if (cached != null) {
                return cached;
            }
            HikariDataSource ds = buildPool(datasourceId);
            pools.put(datasourceId, ds);
            JdbcTemplate created = new JdbcTemplate(ds);
            templates.put(datasourceId, created);
            return created;
        }
    }

    /** 配置变更后丢弃旧池。 */
    public void invalidate(Long datasourceId) {
        templates.remove(datasourceId);
        HikariDataSource ds = pools.remove(datasourceId);
        if (ds != null) {
            ds.close();
        }
    }

    private HikariDataSource buildPool(Long datasourceId) {
        CfgDatasource cfg = datasourceMapper.selectById(datasourceId);
        if (cfg == null) {
            throw new ProcessingException(ErrorCode.NOT_FOUND, "目标数据源不存在: " + datasourceId);
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("mqspg-target-" + datasourceId);
        // 容器里 localhost 指向容器自身；按需纠正主机名（见字段注释）
        hc.setJdbcUrl(rewriteLocalhost(cfg.getJdbcUrl(), localhostOverride));
        hc.setUsername(cfg.getUsername());
        hc.setPassword(passwordCodec.decode(cfg.getPasswordEnc()));
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(5_000);
        // 单条 MERGE 即一个原子事务；批次拆分产生的顺序提交是可接受的（见 tech-design §9.4）
        hc.setAutoCommit(true);
        applyPoolConfig(hc, cfg.getPoolConfig());

        HikariDataSource ds = new HikariDataSource(hc);
        log.info("目标数据源连接池已创建: id={} name={} url={}", datasourceId, cfg.getName(), cfg.getJdbcUrl());
        return ds;
    }

    private void applyPoolConfig(HikariConfig hc, JsonNode poolConfig) {
        if (poolConfig == null || !poolConfig.isObject()) {
            return;
        }
        Iterator<String> it = poolConfig.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            String v = poolConfig.get(k).asText();
            try {
                switch (k) {
                    case "maximumPoolSize" -> hc.setMaximumPoolSize(Integer.parseInt(v));
                    case "minimumIdle" -> hc.setMinimumIdle(Integer.parseInt(v));
                    case "connectionTimeout" -> hc.setConnectionTimeout(Long.parseLong(v));
                    case "idleTimeout" -> hc.setIdleTimeout(Long.parseLong(v));
                    case "maxLifetime" -> hc.setMaxLifetime(Long.parseLong(v));
                    default -> log.warn("忽略未知的连接池参数: {}", k);
                }
            } catch (NumberFormatException e) {
                log.warn("连接池参数 {}={} 无法解析，已忽略", k, v);
            }
        }
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        templates.clear();
        log.info("目标数据源连接池已全部关闭");
    }
}
