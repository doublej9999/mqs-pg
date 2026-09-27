package com.mqspg.retry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 重试策略参数（tech-design §10.4，对应 PRD §26 指数退避）。 */
@Data
@Component
@ConfigurationProperties(prefix = "mqs-pg.retry")
public class RetryProperties {

    /** 最大尝试次数（含首次）。达上限后进入 DLQ。 */
    private int maxAttempt = 10;

    /** 首次重试延迟。 */
    private Duration initialDelay = Duration.ofSeconds(1);

    /** 退避倍数，1s → 2s → 4s → 8s → 16s … */
    private double multiplier = 2.0;

    /** 单次延迟上限。 */
    private Duration maxDelay = Duration.ofSeconds(60);

    /** 抖动比例，±15% 用于打散重试风暴。 */
    private double jitterRatio = 0.15;

    /** 调度轮询间隔。 */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** 单轮领取的任务数上限。 */
    private int batchSize = 200;

    /** 租约时长；超时未完成的任务会被其他实例（或下一轮）重新领取。 */
    private Duration leaseDuration = Duration.ofMinutes(5);

    /**
     * 是否重试 DATA 类错误（tech-design 附录 C-01）。
     *
     * <p>默认 false：转换/解析类错误由消息内容决定，重试必然失败。
     * 若上游是最终一致的（先发事件、后补字段），可打开此项。
     */
    private boolean retryDataErrors = false;
}
