package com.mqspg.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 同步路由：Topic + Tag → Target（cfg_route）。 */
@Data
@TableName("cfg_route")
public class CfgRoute {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String topic;

    private String tag;

    private Long targetId;

    private Integer activeVersion;

    /** DRAFT / VALIDATING / VALID / PUBLISHED / ACTIVE / INACTIVE。 */
    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
