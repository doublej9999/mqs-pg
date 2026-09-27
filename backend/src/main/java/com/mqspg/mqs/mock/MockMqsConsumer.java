package com.mqspg.mqs.mock;

import com.mqspg.mqs.spi.ConsumerSpec;
import com.mqspg.mqs.spi.MqsConsumer;
import com.mqspg.mqs.spi.MqsMessage;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 基于 {@link MockMqsBroker} 的消费者。 */
@RequiredArgsConstructor
public class MockMqsConsumer implements MqsConsumer {

    private final MockMqsBroker broker;
    private final ConsumerSpec spec;

    @Override
    public List<MqsMessage> receive(int maxNum, Duration invisibleDuration) {
        List<MockMessage> got = broker.receive(spec.topic(), spec.tag(), maxNum, invisibleDuration);
        return new ArrayList<>(got);
    }

    @Override
    public void ack(MqsMessage message) {
        if (message instanceof MockMessage m) {
            broker.ack(m);
        }
    }

    @Override
    public void changeInvisibleDuration(MqsMessage message, Duration duration) {
        if (message instanceof MockMessage m) {
            broker.changeInvisible(m, duration);
        }
    }

    @Override
    public void close() {
        // 无底层资源
    }
}
