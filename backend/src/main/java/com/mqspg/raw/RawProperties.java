package com.mqspg.raw;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 原始消息留存参数（tech-design §11，PRD §29 / §32 / §33）。 */
@Data
@Component
@ConfigurationProperties(prefix = "mqs-pg.raw")
public class RawProperties {

    private boolean enabled = true;

    /**
     * 入队缓冲容量。
     *
     * <p>留存是**旁路**：队列满时宁可丢留存，也不能阻塞消费主链路（PRD §29）。
     */
    private int queueCapacity = 10_000;

    private int workerCount = 2;

    /** 留存天数，到期分区 DETACH + DROP。 */
    private int retentionDays = 7;

    /** 每日维护任务。 */
    private String maintenanceCron = "0 30 0 * * *";

    /** 提前创建未来几天的分区。 */
    private int aheadDays = 3;

    /** 单批插入条数。 */
    private int batchSize = 200;

    /** 攒批时间上限。 */
    private Duration flushInterval = Duration.ofMillis(500);

    /** 是否真的删除过期分区。置 false 时只告警，用于灰度观察。 */
    private boolean dropExpired = true;

    /**
     * 超过此字节数的报文**不做 JSON 解析**，直接按原文存 {@code payload_raw}。
     *
     * <p>留存是旁路，解析只为让合法 JSON 落进 JSONB 以便 SQL 查询；
     * 对超大报文而言这次解析纯属浪费，还可能拖慢旁路线程。默认 1 MiB。
     */
    private int payloadParseMaxBytes = 1_048_576;
}
