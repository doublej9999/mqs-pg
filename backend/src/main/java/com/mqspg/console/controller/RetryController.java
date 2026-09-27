package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.retry.RetryService;
import com.mqspg.retry.entity.RtRetryTask;
import com.mqspg.retry.mapper.RtRetryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重试队列的控制台接口（tech-design §13）。
 *
 * <p>定位是「让人能回答三个问题」：积压多少？卡在什么错误上？能不能立刻重放？
 */
@RestController
@RequestMapping("/api/retry")
@RequiredArgsConstructor
public class RetryController {

    private final RetryService retryService;
    private final RtRetryTaskMapper retryTaskMapper;

    /** 积压列表，按下次重试时间升序。 */
    @GetMapping
    public ApiResponse<List<RetryTaskDto>> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "100") int limit) {
        List<RetryTaskDto> data = retryService.backlog(status, limit).stream()
                .map(RetryTaskDto::of)
                .toList();
        return ApiResponse.ok(data);
    }

    /** 按状态汇总，用于控制台概览。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        List<Map<String, Object>> rows = retryTaskMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RtRetryTask>()
                        .select("status", "count(*) as cnt")
                        .groupBy("status"));

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "RUNNING", "SUCCEEDED", "DLQ", "CANCELLED")) {
            counts.put(s, 0L);
        }
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status != null) {
                counts.put(String.valueOf(status), cnt);
            }
        }
        return ApiResponse.ok(counts);
    }

    @GetMapping("/{id}")
    public ApiResponse<RetryTaskDto> get(@PathVariable long id) {
        RtRetryTask task = retryTaskMapper.selectById(id);
        if (task == null) {
            return ApiResponse.fail("NOT_FOUND", "重试任务不存在: " + id);
        }
        return ApiResponse.ok(RetryTaskDto.of(task));
    }

    /**
     * 立即重放（把 {@code next_retry_at} 提前到现在，由调度器下一轮领取）。
     *
     * <p>对已处于 DLQ / CANCELLED 的任务，额外追加一轮重试额度 —— 人工介入
     * 意味着「我知道原因并已修复」，此时保留历史次数只为审计。
     */
    @PostMapping("/{id}/replay")
    public ApiResponse<RetryTaskDto> replay(@PathVariable long id) {
        RtRetryTask task = retryTaskMapper.selectById(id);
        if (task == null) {
            return ApiResponse.fail("NOT_FOUND", "重试任务不存在: " + id);
        }
        retryService.makeDueNow(task);
        return ApiResponse.ok(RetryTaskDto.of(retryTaskMapper.selectById(id)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<RetryTaskDto> cancel(@PathVariable long id,
                                            @RequestParam(defaultValue = "人工取消") String reason) {
        RtRetryTask task = retryTaskMapper.selectById(id);
        if (task == null) {
            return ApiResponse.fail("NOT_FOUND", "重试任务不存在: " + id);
        }
        retryService.cancel(task, reason);
        return ApiResponse.ok(RetryTaskDto.of(retryTaskMapper.selectById(id)));
    }

    /**
     * 对外视图。
     *
     * <p>**刻意不含 {@code payload}**：原始报文可能很大，列表接口把它带出来
     * 会让响应体膨胀几个数量级。需要原文时按 id 单独查库或看 {@code raw_message}。
     */
    public record RetryTaskDto(
            Long id,
            Long routeId,
            Integer configVersion,
            String messageId,
            String topic,
            String tag,
            String errorStage,
            String errorCode,
            String errorMessage,
            Integer attempt,
            Integer maxAttempt,
            String status,
            OffsetDateTime nextRetryAt,
            OffsetDateTime lastErrorAt,
            OffsetDateTime createdAt) {

        static RetryTaskDto of(RtRetryTask t) {
            return new RetryTaskDto(
                    t.getId(), t.getRouteId(), t.getConfigVersion(), t.getMessageId(),
                    t.getTopic(), t.getTag(), t.getErrorStage(), t.getErrorCode(),
                    t.getErrorMessage(), t.getAttempt(), t.getMaxAttempt(), t.getStatus(),
                    t.getNextRetryAt(), t.getLastErrorAt(), t.getCreatedAt());
        }
    }
}
