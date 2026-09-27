package com.mqspg.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 路由配置版本（cfg_version）。
 *
 * <p>{@code content} 是**不可变快照**，运行时唯一事实来源（ADR-06）。
 */
@Data
@TableName(value = "cfg_version", autoResultMap = true)
public class CfgVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeId;

    private Integer version;

    private String status;

    /** 完整配置快照：{@code {mappingStrategy, jslt, mappings[]}}。 */
    @TableField(value = "content", typeHandler = JsonbTypeHandler.class)
    private JsonNode content;

    private String changeNote;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime publishedAt;
}
