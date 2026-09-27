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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
        return ApiResponse.ok(retryService.summary());
    }

    /**
     * 单条详情。
     *
     * <p>与列表 DTO 的区别是**带上 payload**：列表刻意不带（原始报文会让响应体
     * 膨胀几个数量级），而看单条时运维往往正需要原文来判断为什么失败。
     */
    @GetMapping("/{id}")
    public ApiResponse<RetryTaskDetailDto> get(@PathVariable long id) {
        RtRetryTask task = retryTaskMapper.selectById(id);
        if (task == null) {
            return ApiResponse.fail("NOT_FOUND", "重试任务不存在: " + id);
        }
        return ApiResponse.ok(RetryTaskDetailDto.of(task));
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

    /** 单条详情：在列表字段之上补充原始报文与租约信息。 */
    public record RetryTaskDetailDto(
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
            OffsetDateTime leaseUntil,
            OffsetDateTime lastErrorAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String payload) {

        static RetryTaskDetailDto of(RtRetryTask t) {
            return new RetryTaskDetailDto(
                    t.getId(), t.getRouteId(), t.getConfigVersion(), t.getMessageId(),
                    t.getTopic(), t.getTag(), t.getErrorStage(), t.getErrorCode(),
                    t.getErrorMessage(), t.getAttempt(), t.getMaxAttempt(), t.getStatus(),
                    t.getNextRetryAt(), t.getLeaseUntil(), t.getLastErrorAt(),
                    t.getCreatedAt(), t.getUpdatedAt(),
                    // payload 以文本形式返回：前端要展示的是原文，byte[] 会被序列化成数字数组
                    t.getPayload() == null ? null : new String(t.getPayload(), StandardCharsets.UTF_8));
        }
    }
}
