package com.mqspg.config.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** Consumer 运行状态（rt_consumer_state，tech-design §6.1）。 */
@Data
@TableName("rt_consumer_state")
public class RtConsumerState {

    @TableId
    private Long routeId;

    /** RUNNING / PAUSED / RECOVERING / ERROR。 */
    private String status;

    private String reason;

    private Integer bindVersion;

    private OffsetDateTime lastErrorAt;

    private OffsetDateTime lastSuccessAt;

    private OffsetDateTime updatedAt;
}
