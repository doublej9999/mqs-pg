package com.mqspg.mqs;

import com.mqspg.mqs.spi.MqsMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 测试用消息实现。 */
public class FakeMessage implements MqsMessage {

    private final String messageId;
    private final String topic;
    private final String tag;
    private final byte[] body;
    private final Instant bornTime;

    public FakeMessage(String messageId, String body) {
        this(messageId, "test-topic", "test-tag", body, Instant.now());
    }

    public FakeMessage(String messageId, String topic, String tag, String body, Instant bornTime) {
        this.messageId = messageId;
        this.topic = topic;
        this.tag = tag;
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.bornTime = bornTime;
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

    @Override
    public Instant bornTime() {
        return bornTime;
    }

    @Override
    public int deliveryAttempt() {
        return 1;
    }

    @Override
    public String receiptHandle() {
        return "rh-" + messageId;
    }
}
