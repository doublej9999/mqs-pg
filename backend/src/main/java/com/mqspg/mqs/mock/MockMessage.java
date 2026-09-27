package com.mqspg.mqs.mock;

import com.mqspg.mqs.spi.MqsMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** 内存消息。{@code deliveryAttempt} 在每次重新投递时自增。 */
public class MockMessage implements MqsMessage {

    private final String messageId;
    private final String topic;
    private final String tag;
    private final byte[] body;
    private final Instant bornTime;
    private final AtomicInteger deliveryAttempt = new AtomicInteger(0);

    public MockMessage(String messageId, String topic, String tag, byte[] body) {
        this.messageId = messageId;
        this.topic = topic;
        this.tag = tag;
        this.body = body.clone();
        this.bornTime = Instant.now();
    }

    /** 平台层在每次投递时调用。 */
    void markDelivered() {
        deliveryAttempt.incrementAndGet();
    }

    @Override
    public String messageId() {
        return messageId;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String tag() {
        return tag;
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public Instant bornTime() {
        return bornTime;
    }

    @Override
    public int deliveryAttempt() {
        return deliveryAttempt.get();
    }

    @Override
    public String receiptHandle() {
        return "mock-rh-" + messageId + "-" + deliveryAttempt.get();
    }

    @Override
    public String toString() {
        return "MockMessage[" + messageId + " topic=" + topic + " tag=" + tag + "]";
    }
}
