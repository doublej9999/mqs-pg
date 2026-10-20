package com.mqspg.console;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常映射测试（里程碑 H4）。
 *
 * <p>纯单元测试：{@link GlobalExceptionHandler} 没有依赖，直接 new。
 * 这里盯两件事 —— <b>状态码对不对</b>，以及<b>有没有把内部细节泄露出去</b>。
 */
@DisplayName("异常到 HTTP 的映射")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NOT_FOUND → 404，INVALID_INPUT → 400，PG 连不上 → 503")
    void mapsCodesToStatus() {
        assertStatus(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
        assertStatus(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_INPUT);
        assertStatus(HttpStatus.BAD_REQUEST, ErrorCode.CONFLICT);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PG_CONNECTION_ERROR);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PG_TIMEOUT);
        // CONFIG_MISSING 仍是「服务端读不到配置」，不能因为被当过表单错误就降成 400
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CONFIG_MISSING);
    }

    @Test
    @DisplayName("业务异常带 traceId，且不含堆栈/类名")
    void processingExceptionCarriesTraceId() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleProcessing(
                new ProcessingException(ErrorCode.INVALID_INPUT, "Topic 不能为空"));

        ApiResponse<Void> body = r.getBody();
        assertTrue(body != null && body.message().startsWith("Topic 不能为空"));
        assertTrue(body.message().contains("traceId="), () -> "缺少 traceId: " + body.message());
        assertFalse(body.message().contains("ProcessingException"));
        assertEquals("INVALID_INPUT", body.code());
    }

    @Test
    @DisplayName("兜底分支不返回异常原文，只给 traceId")
    void handlerDoesNotLeakInternalMessage() {
        // 模拟一个会把连接串写进 message 的异常
        RuntimeException leaky = new RuntimeException(
                "FATAL: password authentication failed for jdbc:postgresql://db:5432/x");

        ResponseEntity<ApiResponse<Void>> r = handler.handleOther(leaky);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), r.getStatusCode().value());
        String msg = r.getBody().message();
        assertFalse(msg.contains("password"), () -> "泄露了连接信息: " + msg);
        assertFalse(msg.contains("jdbc:"), () -> "泄露了连接串: " + msg);
        assertTrue(msg.contains("traceId="));
    }

    @Test
    @DisplayName("约束冲突不再回吐约束名与 Key 值")
    void integrityDoesNotLeakConstraint() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "duplicate key",
                new RuntimeException("duplicate key value violates unique constraint \"uk_route_topic\" "
                        + "Detail: Key (topic, tag)=(orders, paid) already exists."));

        ResponseEntity<ApiResponse<Void>> r = handler.handleIntegrity(e);

        assertEquals(HttpStatus.BAD_REQUEST.value(), r.getStatusCode().value());
        String msg = r.getBody().message();
        assertFalse(msg.contains("uk_route_topic"), () -> "泄露了约束名: " + msg);
        assertFalse(msg.contains("orders"), () -> "泄露了业务数据: " + msg);
    }

    @Test
    @DisplayName("非法 JSON 与路径参数类型错误都是 400，不再是 500")
    void unreadableAndTypeMismatchAre400() {
        assertEquals(HttpStatus.BAD_REQUEST.value(), handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", (org.springframework.http.HttpInputMessage) null))
                .getStatusCode().value());
        assertEquals(HttpStatus.BAD_REQUEST.value(), handler.handleTypeMismatch(
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", long.class, "id", null, null)).getStatusCode().value());
    }

    /**
     * DTO 上的 {@code @NotBlank} / {@code @Size} 由 Bean Validation 拦下，
     * 落到这个处理器。**字段级提示必须带出去** —— 这类错误的全部价值就在
     * 「哪个字段、为什么」，只说「参数不合法」等于让用户回去自己试。
     */
    @Test
    @DisplayName("Bean Validation 失败带字段级中文提示")
    void validationCarriesFieldDetail() {
        var target = new com.mqspg.config.dto.RouteRequest("", "x".repeat(300), null, null);
        var validator = jakarta.validation.Validation
                .buildDefaultValidatorFactory().getValidator();
        var violations = validator.validate(target);

        MethodArgumentNotValidException e = new MethodArgumentNotValidException(
                null, new org.springframework.validation.BeanPropertyBindingResult(target, "routeRequest"));
        violations.forEach(v -> e.getBindingResult().addError(
                new org.springframework.validation.FieldError("routeRequest", v.getPropertyPath().toString(),
                        v.getMessage())));

        ResponseEntity<ApiResponse<Void>> r = handler.handleValidation(e);

        assertEquals(HttpStatus.BAD_REQUEST.value(), r.getStatusCode().value());
        String msg = r.getBody().message();
        // 三个字段各自的问题都要出现
        assertTrue(msg.contains("name"), () -> "缺 name 的提示: " + msg);
        assertTrue(msg.contains("topic"), () -> "缺 topic 的提示: " + msg);
        assertTrue(msg.contains("targetId"), () -> "缺 targetId 的提示: " + msg);
        // 超长提示要给出具体上限，而不是 PG 的 SQLSTATE 22001 原文
        assertTrue(msg.contains("256"), () -> "应指出长度上限: " + msg);
        assertFalse(msg.contains("22001"), () -> "不该出现数据库原始错误: " + msg);
        assertTrue(msg.contains("traceId="));
    }

    private void assertStatus(HttpStatus expected, ErrorCode code) {
        ResponseEntity<ApiResponse<Void>> r =
                handler.handleProcessing(new ProcessingException(code, "说明"));
        assertEquals(expected.value(), r.getStatusCode().value(), () -> code + " 应映射到 " + expected);
    }
}
