package com.mqspg.console;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.common.error.ProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常到 {@link ApiResponse} 的映射。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProcessingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleProcessing(ProcessingException e) {
        log.warn("业务异常 code={} stage={} message={}", e.getCode(), e.getStage(), e.getMessage());
        return ApiResponse.fail(e.getCode().name(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail("ILLEGAL_ARGUMENT", e.getMessage());
    }

    /**
     * 数据库约束冲突。
     *
     * <p>写接口都对唯一约束做了预检并给出可读信息，走到这里说明是并发下的竞态。
     * 兜底成 400 而不是 500：这仍然是**调用方输入**的问题，不是服务端缺陷，
     * 而且原始信息里带约束名，足以定位是哪一条冲突。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIntegrity(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause().getMessage();
        log.warn("数据库约束冲突: {}", detail);
        return ApiResponse.fail("CONSTRAINT_VIOLATION", "数据库约束冲突: " + detail);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ApiResponse.fail("INTERNAL_ERROR", e.getMessage());
    }
}
