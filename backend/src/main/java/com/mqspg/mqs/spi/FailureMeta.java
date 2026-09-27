package com.mqspg.mqs.spi;

/**
 * 终态失败元数据，用于投递到 DLQ。
 *
 * @param errorStage 失败阶段
 * @param errorCode  错误码
 * @param errorMessage 失败原因
 * @param attempt    已尝试次数
 */
public record FailureMeta(
        String errorStage,
        String errorCode,
        String errorMessage,
        int attempt) {
}
