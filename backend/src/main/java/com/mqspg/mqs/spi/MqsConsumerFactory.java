package com.mqspg.mqs.spi;

/**
 * 消费者工厂：把「用哪个 MQ」这件事收敛到一个可替换的 Bean（ADR-01）。
 *
 * <p>V1 只有 mock 实现；接入平台 MQS 时新增一个实现类并改
 * {@code mqs-pg.mqs.vendor} 即可，业务代码不变。
 */
public interface MqsConsumerFactory {

    /** MQ 实现的标识，如 {@code mock} / {@code platform}。 */
    String vendor();

    MqsConsumer create(ConsumerSpec spec);
}
