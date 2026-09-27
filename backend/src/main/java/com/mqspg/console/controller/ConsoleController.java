package com.mqspg.console.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.service.ConfigService;
import com.mqspg.raw.RawProperties;
import com.mqspg.retry.RetryService;
import com.mqspg.retry.entity.RtErrorRecord;
import com.mqspg.retry.mapper.RtErrorRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台只读视图（tech-design §13.2）。
 *
 * <p>这些接口只做**查询与聚合**：运行总览、消费状态、PG 健康、原始留存、错误流水。
 * 变更类操作各自留在 RouteController / RetryController / MockController。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConsoleController {

    private final ConfigService configService;
    private final RetryService retryService;
    private final RtErrorRecordMapper errorRecordMapper;
    private final JdbcTemplate jdbc;
    private final RawProperties rawProperties;

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

    /** 各路由的消费状态。 */
    @GetMapping("/consumer/state")
    public ApiResponse<List<Map<String, Object>>> consumerStates() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.route_id, s.status, s.reason, s.bind_version,
                       s.last_error_at, s.last_success_at, s.updated_at
                FROM rt_consumer_state s
                ORDER BY s.route_id""");

        List<Map<String, Object>> data = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("routeId", r.get("route_id"));
            m.put("state", r.get("status"));
            m.put("reason", r.get("reason"));
            m.put("bindVersion", r.get("bind_version"));
            m.put("lastErrorAt", r.get("last_error_at"));
            m.put("lastSuccessAt", r.get("last_success_at"));
            m.put("updatedAt", r.get("updated_at"));
            return m;
        }).toList();

        return ApiResponse.ok(data);
    }

    /** PG 连通性探测。控制台用它显示「数据库现在是否可用」。 */
    @GetMapping("/pg/health")
    public ApiResponse<Map<String, Object>> pgHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            String version = jdbc.queryForObject("SELECT version()", String.class);
            data.put("healthy", true);
            data.put("detail", version);
            data.put("serverTime", jdbc.queryForObject("SELECT now()", String.class));
        } catch (RuntimeException e) {
            data.put("healthy", false);
            data.put("detail", e.getMessage());
        }
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
        int offset = Math.max(0, page - 1) * size;

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
                        .last("LIMIT " + size + " OFFSET " + offset));

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
        int offset = Math.max(0, page - 1) * size;

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
        pageArgs.add(offset);
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
