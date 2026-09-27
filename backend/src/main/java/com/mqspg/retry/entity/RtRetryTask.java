package com.mqspg.retry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 重试任务（rt_retry_task），应用侧重试的唯一持久载体（ADR-02）。 */
@Data
@TableName("rt_retry_task")
public class RtRetryTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeId;

    private Integer configVersion;

    private String messageId;

    private String topic;

    private String tag;

    /** 原始消息体，用于独立重放。 */
    private byte[] payload;

    private String errorStage;

    private String errorCode;

    private String errorMessage;

    private Integer attempt;

    private Integer maxAttempt;

    private OffsetDateTime nextRetryAt;

    /** PENDING / RUNNING / SUCCEEDED / DLQ / CANCELLED。 */
    private String status;

    private OffsetDateTime leaseUntil;

    private OffsetDateTime lastErrorAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
