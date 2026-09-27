package com.mqspg.common.error;

import lombok.Getter;

/**
 * 处理链路中可预期的业务异常。携带错误码，供重试/ DLQ 决策使用。
 */
@Getter
public class ProcessingException extends RuntimeException {

    private final ErrorCode code;
    private final ErrorStage stage;
    private final ErrorSeverity severity;

    public ProcessingException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ProcessingException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.stage = code.getStage();
        this.severity = code.getSeverity();
    }

    /** PG 级故障：需暂停消费，不 ACK。 */
    public boolean isFatal() {
        return severity == ErrorSeverity.FATAL;
    }

    /** 可重试：进重试表。 */
    public boolean isTransient() {
        return severity == ErrorSeverity.TRANSIENT;
    }

    /** 数据问题：默认直接 DLQ。 */
    public boolean isDataError() {
        return severity == ErrorSeverity.DATA;
    }
}
