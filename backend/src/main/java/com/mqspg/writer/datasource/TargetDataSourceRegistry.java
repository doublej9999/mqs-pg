package com.mqspg.writer.datasource;

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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
            throw new ProcessingException(ErrorCode.CONFIG_MISSING, "目标数据源不存在: " + datasourceId);
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("mqspg-target-" + datasourceId);
        hc.setJdbcUrl(cfg.getJdbcUrl());
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
