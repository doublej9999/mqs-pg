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
}
