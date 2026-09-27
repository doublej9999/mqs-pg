package com.mqspg.raw;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原始消息分区的创建与回收（tech-design §11.3，PRD §32 / §33）。
 *
 * <p>用 PostgreSQL **原生 RANGE 日分区**，TTL 靠 {@code DETACH + DROP} 实现：
 * 删整分区是一次元数据操作，不会像 {@code DELETE} 那样产生大量死元组与 WAL。
 *
 * <p><b>DEFAULT 分区是个陷阱</b>：PostgreSQL 不允许创建与 DEFAULT 分区中已有行
 * 冲突的新分区，会报
 * {@code updated partition constraint for default partition would be violated}。
 * 而 DEFAULT 里之所以会有数据，恰恰是因为**那一刻分区还没建**：首次上线、
 * 维护任务尚未运行、或某天在维护跑之前就来了流量，都会如此。
 *
 * <p>所以碰到冲突不能「告警并跳过」—— 那样这个区间就永远建不出分区了。
 * 这里改为调用 {@link #repairAndCreate} 把冲突行搬进新分区，见该方法的说明。
 *
 * <p><b>分区边界与会话时区绑定</b>：「今天」取自 PostgreSQL 的 {@code now()::date}，
 * 边界字面量也按同一会话时区解释，二者始终自洽。但 pgjdbc 的会话时区来自 JVM
 * 默认时区，因此部署之间若改动 JVM 时区，新旧分区的绝对边界会错位。那种情况下
 * CREATE 会报区间重叠，这里会记录可操作的诊断而不是抛栈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawPartitionMaintenance {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 分区名即 {@code raw_message_p<yyyyMMdd>}，日期直接来自名字，不依赖时区渲染。 */
    private static final Pattern PARTITION_NAME = Pattern.compile("^raw_message_p(\\d{8})$");

    private static final String PARTITIONS_SQL = """
            SELECT c.relname AS name
            FROM pg_inherits i
            JOIN pg_class c      ON c.oid = i.inhrelid
            JOIN pg_class p      ON p.oid = i.inhparent
            JOIN pg_namespace n  ON n.oid = p.relnamespace
            WHERE p.relname = 'raw_message'
              AND n.nspname = current_schema()
              AND c.relname LIKE 'raw_message_p%'
            ORDER BY c.relname""";

    private static final String DEFAULT_ROWS_SQL = """
            SELECT count(*) FROM raw_message_default
            WHERE receive_time >= (?::timestamptz) AND receive_time < (?::timestamptz)""";

    private final JdbcTemplate jdbc;
    private final RawProperties props;
    private final PlatformTransactionManager txManager;

    @Scheduled(cron = "${mqs-pg.raw.maintenance-cron:0 30 0 * * *}")
    public void maintain() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            int created = createFuturePartitions();
            int dropped = dropExpiredPartitions();
            log.info("原始留存分区维护完成: 新建 {} 个，回收 {} 个（保留 {} 天）",
                    created, dropped, props.getRetentionDays());
        } catch (RuntimeException e) {
            log.error("原始留存分区维护失败", e);
        }
    }

    /** 提前创建今天起未来 N 天的分区。 */
    public int createFuturePartitions() {
        int created = 0;
        LocalDate today = today();
        for (int offset = 0; offset <= Math.max(0, props.getAheadDays()); offset++) {
            LocalDate day = today.plusDays(offset);
            if (ensurePartition(day)) {
                created++;
            }
        }
        return created;
    }

    /**
     * 确保某天的分区存在。
     *
     * @return true 表示本次真的创建了
     */
    public boolean ensurePartition(LocalDate day) {
        String name = partitionName(day);

        if (partitionExists(name)) {
            return false;
        }

        Integer conflicting = defaultRowsIn(day);
        if (conflicting != null && conflicting > 0) {
            // DEFAULT 里已经有这一天的数据（说明当天的分区在写入发生时还不存在）。
            // 此时直接 CREATE 会被 PG 拒绝，必须先把它挪出来。
            log.warn("DEFAULT 分区中有 {} 行落在 {}，先搬移再创建分区 {}", conflicting, day, name);
            return repairAndCreate(day, name, conflicting);
        }

        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS %s PARTITION OF raw_message FOR VALUES FROM ('%s') TO ('%s')"
                    .formatted(name, day, day.plusDays(1)));
            log.info("已创建原始留存分区: {} ({} ~ {})", name, day, day.plusDays(1));
            return true;
        } catch (RuntimeException e) {
            log.error("创建分区 {} 失败（区间 {} ~ {}）。若报 overlap，通常是 JVM 默认时区"
                            + "与既有分区的边界不一致所致，需要人工核对后重建；"
                            + "维护任务会跳过它并继续处理其它日期。",
                    name, day, day.plusDays(1), e);
            return false;
        }
    }

    /**
     * 把 DEFAULT 分区中某天的数据搬进独立分区，然后创建该分区。
     *
     * <p><b>为什么必须这么绕</b>：PostgreSQL 不允许创建与 DEFAULT 分区中已有行
     * 冲突的新分区（会报 {@code updated partition constraint for default partition
     * would be violated}）。而 DEFAULT 里的数据恰恰是「分区还没建就来了消息」造成的。
     * 如果只是告警跳过，这个区间就**永远**建不出分区了 —— 首次上线、维护任务没跑、
     * 或某天 00:30 之前就有流量，都会踩中。
     *
     * <p>因此采用官方推荐的搬移流程，整个过程一个事务：
     * <ol>
     *   <li>{@code DETACH} DEFAULT —— 解除约束，否则下一步无法创建；</li>
     *   <li>创建该天的分区；</li>
     *   <li>把属于该天的行从已分离的表搬进新分区（保留原 id）；</li>
     *   <li>从已分离的表里删除这些行；</li>
     *   <li>{@code ATTACH} 回 DEFAULT。</li>
     * </ol>
     *
     * <p>代价是 DETACH 会对父表加 ACCESS EXCLUSIVE 锁，期间写入短暂阻塞。
     * 相比「永久卡死」，这个代价在每日 00:30 的维护窗口里完全可以接受。
     */
    private boolean repairAndCreate(LocalDate day, String name, int rowCount) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                jdbc.execute("ALTER TABLE raw_message DETACH PARTITION raw_message_default");

                jdbc.execute("CREATE TABLE IF NOT EXISTS %s PARTITION OF raw_message FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(name, day, day.plusDays(1)));

                int moved = jdbc.update("""
                        INSERT INTO %s (id, message_id, route_id, topic, tag, receive_time, config_version, payload, payload_raw)
                        SELECT id, message_id, route_id, topic, tag, receive_time, config_version, payload, payload_raw
                        FROM raw_message_default
                        WHERE receive_time >= ?::timestamptz AND receive_time < ?::timestamptz
                        ON CONFLICT DO NOTHING""".formatted(name),
                        day.atStartOfDay().toString(), day.plusDays(1).atStartOfDay().toString());

                jdbc.update("""
                        DELETE FROM raw_message_default
                        WHERE receive_time >= ?::timestamptz AND receive_time < ?::timestamptz""",
                        day.atStartOfDay().toString(), day.plusDays(1).atStartOfDay().toString());

                jdbc.execute("ALTER TABLE raw_message ATTACH PARTITION raw_message_default DEFAULT");
                log.info("已把 DEFAULT 中的 {} 行搬入分区 {}（预计 {} 行）", moved, name, rowCount);
            });
            return true;
        } catch (RuntimeException e) {
            log.error("搬移 DEFAULT 数据并创建分区 {} 失败；DEFAULT 分区已由事务回滚保护", name, e);
            return false;
        }
    }

    private boolean partitionExists(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relname = ? AND n.nspname = current_schema()",
                Integer.class, name);
        return n != null && n > 0;
    }

    private Integer defaultRowsIn(LocalDate day) {
        if (!partitionExists("raw_message_default")) {
            return 0;
        }
        return jdbc.queryForObject(DEFAULT_ROWS_SQL, Integer.class,
                day.atStartOfDay().toString(), day.plusDays(1).atStartOfDay().toString());
    }

    private static String partitionName(LocalDate day) {
        return "raw_message_p" + day.format(DATE);
    }

    /** 从分区名解析出它覆盖的那一天；命名不符合约定时返回 null。 */
    private static LocalDate partitionDate(String name) {
        Matcher m = PARTITION_NAME.matcher(name);
        return m.matches() ? LocalDate.parse(m.group(1), DATE) : null;
    }

    /** 回收超出保留期的分区。 */
    public int dropExpiredPartitions() {
        String cutoff = jdbc.queryForObject(
                "SELECT to_char((now() - make_interval(days => ?))::date, 'YYYY-MM-DD')",
                String.class, props.getRetentionDays());
        LocalDate limit = LocalDate.parse(cutoff);

        int dropped = 0;
        for (Map<String, Object> row : jdbc.queryForList(PARTITIONS_SQL)) {
            String name = String.valueOf(row.get("name"));
            LocalDate day = partitionDate(name);
            if (day == null) {
                log.warn("分区 {} 命名不符合 raw_message_p<yyyyMMdd> 约定，跳过以免误删", name);
                continue;
            }
            // 分区覆盖 [day, day+1)。只有整个区间都落在保留线之前才回收，
            // 宁可多留一天，也不能删掉仍在保留期内的数据。
            if (!day.plusDays(1).isBefore(limit)) {
                continue;
            }
            if (!props.isDropExpired()) {
                log.warn("分区 {} 已过期（{} < 保留线 {}），但 drop-expired=false，仅告警",
                        name, day, cutoff);
                continue;
            }
            try {
                // DETACH 只是元数据操作；脱离后成为独立表，再 DROP 就不必锁住父表
                jdbc.execute("ALTER TABLE raw_message DETACH PARTITION " + name);
                jdbc.execute("DROP TABLE " + name);
                dropped++;
                log.info("已回收过期分区: {} ({} < 保留线 {})", name, day, cutoff);
            } catch (RuntimeException e) {
                log.error("回收分区 {} 失败", name, e);
            }
        }
        return dropped;
    }

    /** 分区清单（名称 + 由名称解析出的日期），供控制台查看。 */
    public List<Map<String, Object>> listPartitions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(PARTITIONS_SQL)) {
            String name = String.valueOf(row.get("name"));
            LocalDate day = partitionDate(name);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("partition_date", day == null ? null : day.toString());
            rows.add(out);
        }
        return rows;
    }

    /** 当前 DEFAULT 分区行数。长期非 0 说明分区维护没跟上。 */
    public long defaultPartitionRows() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM raw_message_default", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 用数据库所在时区算「今天」。
     *
     * <p>分区边界由 PostgreSQL 按会话时区解释，用 JVM 的默认时区算会产生
     * 跨时区的日期错位（本地实测服务器为 +08）。
     */
    private LocalDate today() {
        String s = jdbc.queryForObject("SELECT to_char(now()::date, 'YYYY-MM-DD')", String.class);
        return LocalDate.parse(s);
    }
}
