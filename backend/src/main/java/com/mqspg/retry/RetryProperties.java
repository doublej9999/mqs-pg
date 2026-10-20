package com.mqspg.retry;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

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
     * 单条任务的最坏预估耗时（J2）。用于按批大小放大初始租约。
     *
     * <p>领取时一次给整批打同一个租约，然后顺序处理 batchSize 条。
     * 若目标库慢，200 条 × 每条一个连接超时（默认 5s）远超 5 分钟租约，
     * {@code reclaimExpiredLeases} 会把**仍在处理**的任务重新放回 PENDING，
     * 同一任务被重复重放（{@code uk_retry_active} 只防并存，不防重放）。
     *
     * <p>默认 2s：比正常单条（毫秒级）宽松得多，比连接超时小，
     * 用来把「批很大」这件事折算进租约。
     */
    private Duration estimatedPerTask = Duration.ofSeconds(2);

    /**
     * 每处理多少条续租一次（J2）。0 表示不续租，仅靠初始租约。
     *
     * <p>续租才是真正的兜底：初始租约只能按「预估」放大，而实际耗时无法预估。
     * 续租失败（返回行数不符）即停止本轮，把剩余任务留给下一轮，
     * 避免在租约已失的情况下继续处理、与其他实例重复重放。
     */
    private int renewLeaseEvery = 20;

    /**
     * 绑定配置**永久**缺失时允许缓冲的轮数，超过即进 DLQ。
     *
     * <p>为什么需要这个上限：重试任务按**任务绑定的版本**取配置，
     * 版本被删后每轮重放都失败。若把它当作「暂时问题」无限释放，
     * {@code next_retry_at} 只会往后推，任务永远不消耗次数、永远进不了 DLQ ——
     * 表只增不减，且每分钟刷一条 warn。
     *
     * <p>为什么不是 0：运维可能正要恢复那个版本（重新发布同一版本号），
     * 直接丢消息比多留几分钟更糟。3 轮 ≈ 3 × 3 × pollInterval。
     */
    private int configGoneBuffer = 3;

    /**
     * 是否重试 DATA 类错误（tech-design 附录 C-01）。
     *
     * <p>默认 false：转换/解析类错误由消息内容决定，重试必然失败。
     * 若上游是最终一致的（先发事件、后补字段），可打开此项。
     */
    private boolean retryDataErrors = false;
}
