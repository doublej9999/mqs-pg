package com.mqspg.console.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.service.ConfigService;
import com.mqspg.consumer.ConsumerManager;
import com.mqspg.consumer.PgHealthGate;
import com.mqspg.consumer.RouteConsumer;
import com.mqspg.raw.RawProperties;
import com.mqspg.retry.RetryService;
import com.mqspg.retry.entity.RtErrorRecord;
import com.mqspg.retry.mapper.RtErrorRecordMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 控制台只读视图（tech-design §13.2）。
 *
 * <p>这些接口只做**查询与聚合**：运行总览、消费状态、PG 健康、原始留存、错误流水。
 * 变更类操作各自留在 RouteController / RetryController / MockController。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConsoleController {

    private final ConfigService configService;
    private final RetryService retryService;
    private final RtErrorRecordMapper errorRecordMapper;
    private final JdbcTemplate jdbc;
    private final RawProperties rawProperties;
    private final ConsumerManager consumerManager;
    private final PgHealthGate healthGate;

    /** 运行总览。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        List<RouteSummaryDto> routes = configService.listRoutes();
        long active = routes.stream()
                .filter(r -> "ACTIVE".equalsIgnoreCase(r.status()))
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRoutes", routes.size());
        data.put("activeRoutes", active);
        data.put("retry", retryService.summary());
        data.put("rawRetentionEnabled", rawProperties.isEnabled());
        data.put("rawRetentionDays", rawProperties.getRetentionDays());
        return ApiResponse.ok(data);
    }

    /**
     * 各路由的消费状态。
     *
     * <p>「已接收 / 已 ACK / 未 ACK / 批次」来自 {@link RouteConsumer} 的**内存计数**，
     * 因此语义是「本次启动以来」：进程重启即归零，且多实例部署时只反映本实例。
     * 没有运行中 Consumer 的路由返回 {@code null}（页面显示 '-'），
     * 不用 0 冒充「确实没消息」—— 这两件事在排障时完全不同。
     */
    @GetMapping("/consumer/state")
    public ApiResponse<List<Map<String, Object>>> consumerStates() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.route_id, s.status, s.reason, s.bind_version,
                       s.last_error_at, s.last_success_at, s.updated_at
                FROM rt_consumer_state s
                ORDER BY s.route_id""");

        List<Map<String, Object>> data = rows.stream().map(r -> {
            long routeId = ((Number) r.get("route_id")).longValue();
            RouteConsumer c = consumerManager.consumerOf(routeId);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("routeId", routeId);
            m.put("state", r.get("status"));
            m.put("reason", r.get("reason"));
            m.put("bindVersion", r.get("bind_version"));
            m.put("lastErrorAt", r.get("last_error_at"));
            m.put("lastSuccessAt", r.get("last_success_at"));
            m.put("updatedAt", r.get("updated_at"));
            m.put("receivedTotal", c == null ? null : c.receivedCount());
            m.put("ackedTotal", c == null ? null : c.ackedCount());
            m.put("failedTotal", c == null ? null : c.noAckCount());
            m.put("batchTotal", c == null ? null : c.batchCount());
            m.put("pauseReason", c == null ? null : c.pauseReason());
            return m;
        }).toList();

        return ApiResponse.ok(data);
    }

    /**
     * PG 健康聚合视图。
     *
     * <p><b>原来是「探配置库，然后把异常原文回吐」</b>，有三个毛病：
     * <ol>
     *   <li>控制台这张卡叫「PG 健康」，运维默认理解为**目标库**，实际探的是配置库 ——
     *       目标库挂了这张卡仍是「正常」，正好漏掉最该报的那一种故障；</li>
     *   <li>与 {@link PgHealthGate} 的真实状态（各数据源的闸门）完全脱节；</li>
     *   <li>{@code e.getMessage()} 直接返回前端，可能带连接串。</li>
     * </ol>
     *
     * <p>现在返回三块：{@code configDb}（配置库探活）、{@code targets}（各数据源闸门）、
     * {@code healthy}（两者皆正常才 true）。失败原因统一为固定文案，细节只进日志。
     * 仍然用 {@code ApiResponse.ok} 包装 —— 这是「探测结果」而不是「接口失败」，
     * 配置库挂掉时接口本身也要能返回结构，否则前端连「挂了」都显示不出来。
     */
    @GetMapping("/pg/health")
    public ApiResponse<Map<String, Object>> pgHealth() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1) 配置库：一次轻量查询即可，不查 version() —— 那个字符串对排障没有增量信息
        Map<String, Object> configDb = new LinkedHashMap<>();
        try {
            configDb.put("healthy", true);
            configDb.put("detail", jdbc.queryForObject("SELECT now()", String.class));
        } catch (RuntimeException e) {
            // 失败原因不进响应体（可能含连接串），只进日志
            log.warn("配置库探活失败", e);
            configDb.put("healthy", false);
            configDb.put("detail", "配置库不可用");
        }
        data.put("configDb", configDb);

        // 2) 目标库：按数据源汇总闸门状态
        Map<Long, Boolean> gates = healthGate.snapshot();
        List<Map<String, Object>> targets = new ArrayList<>();
        boolean anyTargetClosed = false;
        for (Map.Entry<Long, Boolean> e : gates.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("datasourceId", e.getKey());
            t.put("healthy", e.getValue());
            if (!e.getValue()) {
                anyTargetClosed = true;
                // 闸门自己记着上一次的失败原因，拿来当 detail 足够定位
                t.put("detail", "写入失败已达阈值，闸门已关闭");
            }
            targets.add(t);
        }
        data.put("targets", targets);

        // 3) 总状态：两者皆正常才 true。未出现过写入尝试的数据源不参与判定
        //    （未知默认放行，不构成不健康）
        data.put("healthy", Boolean.TRUE.equals(configDb.get("healthy")) && !anyTargetClosed);
        return ApiResponse.ok(data);
    }

    /** 错误与 DLQ 流水，分页。 */
    @GetMapping("/errors")
    public ApiResponse<Map<String, Object>> errors(
            @RequestParam(required = false) Boolean isFinal,
            @RequestParam(required = false) String errorStage,
            @RequestParam(required = false) String messageId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {

        int size = Math.max(1, Math.min(limit, 200));
        // 用 long 计算（J6）：page=Integer.MAX_VALUE 时 (page-1)*size 会整数溢出为负数，
        // 拼进 SQL 的 OFFSET 变成负数 -> PG 报错 -> 500。这里先按 long 算，再夹到 int 范围。
        long offset = (long) Math.max(0, page - 1) * size;
        int safeOffset = offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;

        LambdaQueryWrapper<RtErrorRecord> countQuery = new LambdaQueryWrapper<RtErrorRecord>()
                .eq(isFinal != null, RtErrorRecord::getFinalFlag, isFinal)
                .eq(errorStage != null && !errorStage.isBlank(), RtErrorRecord::getErrorStage, errorStage)
                .eq(messageId != null && !messageId.isBlank(), RtErrorRecord::getMessageId, messageId);

        Long total = errorRecordMapper.selectCount(countQuery);

        List<RtErrorRecord> items = errorRecordMapper.selectList(
                new LambdaQueryWrapper<RtErrorRecord>()
                        .eq(isFinal != null, RtErrorRecord::getFinalFlag, isFinal)
                        .eq(errorStage != null && !errorStage.isBlank(), RtErrorRecord::getErrorStage, errorStage)
                        .eq(messageId != null && !messageId.isBlank(), RtErrorRecord::getMessageId, messageId)
                        .orderByDesc(RtErrorRecord::getId)
                        .last("LIMIT " + size + " OFFSET " + safeOffset));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total == null ? 0 : total);
        data.put("items", items.stream().map(ConsoleController::toErrorView).toList());
        return ApiResponse.ok(data);
    }

    /** 原始留存查询，分页。 */
    @GetMapping("/raw")
    public ApiResponse<Map<String, Object>> raw(
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) Long routeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {

        int size = Math.max(1, Math.min(limit, 200));
        // 用 long 计算（J6）：page=Integer.MAX_VALUE 时 (page-1)*size 会整数溢出为负数，
        // 拼进 SQL 的 OFFSET 变成负数 -> PG 报错 -> 500。这里先按 long 算，再夹到 int 范围。
        long offset = (long) Math.max(0, page - 1) * size;
        int safeOffset = offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (messageId != null && !messageId.isBlank()) {
            where.append(" AND message_id = ?");
            args.add(messageId);
        }
        if (routeId != null) {
            where.append(" AND route_id = ?");
            args.add(routeId);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM raw_message" + where, Long.class, args.toArray());

        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(safeOffset);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, message_id, route_id, topic, tag, receive_time, config_version, "
                        + "payload::text AS payload, payload_raw "
                        + "FROM raw_message" + where
                        + " ORDER BY receive_time DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());

        List<Map<String, Object>> view = items.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("messageId", r.get("message_id"));
            m.put("routeId", r.get("route_id"));
            m.put("topic", r.get("topic"));
            m.put("tag", r.get("tag"));
            m.put("receiveTime", r.get("receive_time"));
            m.put("configVersion", r.get("config_version"));
            m.put("payload", r.get("payload"));
            m.put("payloadRaw", r.get("payload_raw"));
            return m;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total == null ? 0 : total);
        data.put("items", view);
        return ApiResponse.ok(data);
    }

    private static Map<String, Object> toErrorView(RtErrorRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("routeId", r.getRouteId());
        m.put("configVersion", r.getConfigVersion());
        m.put("messageId", r.getMessageId());
        m.put("topic", r.getTopic());
        m.put("tag", r.getTag());
        m.put("errorStage", r.getErrorStage());
        m.put("errorCode", r.getErrorCode());
        m.put("errorMessage", r.getErrorMessage());
        m.put("retryCount", r.getRetryCount());
        m.put("finalFlag", r.getFinalFlag());
        m.put("replayedAt", r.getReplayedAt());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
