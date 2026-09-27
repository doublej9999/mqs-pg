package com.mqspg.writer.batch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 批次参数（PRD §9：条数或时间先到者为准）。 */
@Data
@Component
@ConfigurationProperties(prefix = "mqs-pg.batch")
public class BatchProperties {

    /** 触发写入的条数阈值。 */
    private int size = 1000;

    /** 触发写入的时间阈值。 */
    private Duration flushInterval = Duration.ofSeconds(1);

    /** 单次拉取条数。 */
    private int pullSize = 500;

    /**
     * 消息不可见时长。
     *
     * <p><b>必须大于预期批处理耗时</b>：否则消息会在处理完成前重新可见，
     * 被并发消费，造成同一批次内的重复处理。
     */
    private Duration invisibleDuration = Duration.ofSeconds(30);

    /** 单条 MERGE 语句覆盖的最大行数。 */
    private int writeChunkSize = 200;
}
