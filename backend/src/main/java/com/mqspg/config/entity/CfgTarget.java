package com.mqspg.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 同步目标表定义（cfg_target）。 */
@Data
@TableName(value = "cfg_target", autoResultMap = true)
public class CfgTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long datasourceId;

    private String schemaName;

    private String tableName;

    /** Upsert Key 列名数组，如 {@code ["id"]}。 */
    @TableField(value = "upsert_keys", typeHandler = JsonbTypeHandler.class)
    private JsonNode upsertKeys;

    private String updateTimeField;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
