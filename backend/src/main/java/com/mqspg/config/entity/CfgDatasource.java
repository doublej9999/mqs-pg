package com.mqspg.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 目标 PostgreSQL 数据源（cfg_datasource）。 */
@Data
@TableName(value = "cfg_datasource", autoResultMap = true)
public class CfgDatasource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String jdbcUrl;

    private String username;

    /** {@code {aes}<base64>} 为加密存储；{@code {noop}<plain>} 仅限 dev。 */
    private String passwordEnc;

    @TableField(value = "pool_config", typeHandler = JsonbTypeHandler.class)
    private JsonNode poolConfig;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
