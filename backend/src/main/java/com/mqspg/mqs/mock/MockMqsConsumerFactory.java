package com.mqspg.mqs.mock;

import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsConsumerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 内存 MQ 实现。{@code mqs-pg.mqs.vendor=mock}（默认）时生效。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mqs-pg.mqs.vendor", havingValue = "mock", matchIfMissing = true)
public class MockMqsConsumerFactory implements MqsConsumerFactory {

    private final MockMqsBroker broker;

    @Override
    public String vendor() {
        return "mock";
    }

    @Override
    public MqsConsumer create(ConsumerSpec spec) {
        return new MockMqsConsumer(broker, spec);
    }
}
