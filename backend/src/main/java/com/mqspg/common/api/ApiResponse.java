package com.mqspg.common.api;

/**
 * 统一 API 响应包装。
 *
 * @param success 是否成功
 * @param code    业务码，成功为 {@code OK}
 * @param message 失败原因
 * @param data    载荷
 */
public record ApiResponse<T>(boolean success, String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", null, data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, "OK", null, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    /**
     * 失败响应，并把 {@code traceId} 附在 message 末尾。
     *
     * <p>为什么放 message 而不是新增字段：前端（K3）已经按 {@code message} 直接展示，
     * 加字段意味着前后端同时改契约；而 traceId 恰恰是**不可用时最需要**的东西 ——
     * 用户报障时能把这串给运维，运维 grep 日志就能拿到完整堆栈。
     * 对外只给「一句中文 + traceId」，内部细节（SQL / 连接串 / 类名）只进日志。
     */
    public static <T> ApiResponse<T> fail(String code, String message, String traceId) {
        String text = traceId == null || traceId.isBlank()
                ? message
                : "%s（traceId=%s）".formatted(message, traceId);
        return new ApiResponse<>(false, code, text, null);
    }
}
