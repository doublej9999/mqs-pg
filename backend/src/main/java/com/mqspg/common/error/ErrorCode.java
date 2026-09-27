package com.mqspg.common.error;

import lombok.Getter;

/**
 * 错误码及其默认级别（tech-design.md §8.6）。
 *
 * <p>注：{@link ErrorSeverity#DATA} 类错误默认直接进 DLQ（不重试）。PRD §23 字面要求
 * 「数据处理错误重试 10 次」，该行为可通过路由级开关 {@code retryDataErrors} 恢复，
 * 见 tech-design.md 附录 C-01。
 */
@Getter
public enum ErrorCode {

    // ---- 数据/转换类 ----
    JSON_PARSE_ERROR(ErrorStage.PARSE, ErrorSeverity.DATA),
    JSLT_ERROR(ErrorStage.JSLT, ErrorSeverity.DATA),
    PATH_ERROR(ErrorStage.PATH, ErrorSeverity.TRANSIENT),
    TYPE_CONVERSION_ERROR(ErrorStage.CONVERT, ErrorSeverity.TRANSIENT),
    ENUM_MAPPING_ERROR(ErrorStage.CONVERT, ErrorSeverity.DATA),
    DATETIME_FORMAT_ERROR(ErrorStage.CONVERT, ErrorSeverity.DATA),
    EXPRESSION_ERROR(ErrorStage.EXPRESSION, ErrorSeverity.TRANSIENT),
    MISSING_REQUIRED_FIELD(ErrorStage.VALIDATE, ErrorSeverity.DATA),

    // ---- 写入类 ----
    PG_CONSTRAINT_VIOLATION(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),
    PG_TYPE_ERROR(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),
    PG_CONNECTION_ERROR(ErrorStage.WRITE, ErrorSeverity.FATAL),
    PG_TIMEOUT(ErrorStage.WRITE, ErrorSeverity.FATAL),
    PG_UNKNOWN(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),

    // ---- 系统类 ----
    CONFIG_MISSING(ErrorStage.RECEIVE, ErrorSeverity.FATAL),
    INTERNAL_ERROR(ErrorStage.RECEIVE, ErrorSeverity.TRANSIENT);

    private final ErrorStage stage;
    private final ErrorSeverity severity;

    ErrorCode(ErrorStage stage, ErrorSeverity severity) {
        this.stage = stage;
        this.severity = severity;
    }
}
