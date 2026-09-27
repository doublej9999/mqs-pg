package com.mqspg.retry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 错误记录（rt_error_record）；{@code isFinal = true} 表示 DLQ 终态（PRD §31）。 */
@Data
@TableName("rt_error_record")
public class RtErrorRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeId;

    private Integer configVersion;

    private String messageId;

    private String topic;

    private String tag;

    private byte[] payload;

    private OffsetDateTime errorTime;

    private String errorStage;

    private String errorCode;

    private String errorMessage;

    private Integer retryCount;

    /**
     * true = 已达最大重试次数，进入 DLQ。
     *
     * <p>字段名故意不用 {@code isFinal}：Lombok 对 {@code Boolean isFinal} 生成的是
     * {@code getIsFinal/setIsFinal}，与 MyBatis 的 {@code is_final → isFinal} 推断叠在一起
     * 容易产生歧义，故显式映射列名。
     */
    @TableField("is_final")
    private Boolean finalFlag;

    private OffsetDateTime replayedAt;

    private OffsetDateTime createdAt;
}
