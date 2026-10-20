package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ErrorStage;
import com.mqspg.consumer.ConsumerStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举与元数据的只读视图。
 *
 * <p>存在的理由是**让前端不必硬编码后端的枚举**：错误阶段、消费状态、重试状态
 * 这些值域一旦变化，硬编码的筛选项会静默失效 —— 页面上表现为「某个选项永远查不到数据」，
 * 而不是报错。这类缺陷比崩溃更难被发现，因此在这里统一暴露。
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetaController {

    /**
     * 错误阶段与错误码的取值域。
     *
     * <p>同时给出 {@code code → stage} 的映射：页面要在「按阶段筛选」之外
     * 展示某个错误码属于哪个阶段时，不需要再写一份对照表。
     */
    @GetMapping("/error-stages")
    public ApiResponse<Map<String, Object>> errorStages() {
        List<String> stages = Arrays.stream(ErrorStage.values()).map(Enum::name).toList();

        Map<String, Object> codeToStage = new LinkedHashMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            codeToStage.put(code.name(), code.getStage().name());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stages", stages);
        data.put("codeStages", codeToStage);
        return ApiResponse.ok(data);
    }

    /** 消费状态与重试状态的取值域，供页面渲染状态标签与下拉框。 */
    @GetMapping("/statuses")
    public ApiResponse<Map<String, Object>> statuses() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("consumerStates", List.of(
                ConsumerStateRepository.RUNNING,
                ConsumerStateRepository.PAUSED,
                ConsumerStateRepository.RECOVERING,
                ConsumerStateRepository.ERROR));
        data.put("retryStatuses", List.of("PENDING", "RUNNING", "SUCCEEDED", "DLQ", "CANCELLED"));
        return ApiResponse.ok(data);
    }
}
