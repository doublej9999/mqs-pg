package com.mqspg.mqs.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内消息代理，模拟平台 MQS 的「批量拉取 + 显式 ACK + 不可见时长」语义。
 *
 * <p>存在的意义不是「假装有 MQ」，而是让**消费侧的语义**（重复投递、乱序、
 * 不可见窗口、ACK 丢失）可以在单元测试里被确定性地构造出来。
 *
 * <p>不可见时长采用**惰性清扫**：不跑定时线程，而是在每次 {@code receive} 时
 * 把已过期的在途消息放回队列。这样测试无需等待真实时间流逝，也避免了后台线程。
 */
@Slf4j
@Component
public class MockMqsBroker {

    /** topic → 待投递队列（保持 FIFO） */
    private final Map<String, Deque<MockMessage>> pending = new ConcurrentHashMap<>();

    /** messageId → 在途消息 */
    private final Map<String, MockMessage> inflight = new ConcurrentHashMap<>();

    /** messageId → 不可见到期时刻 */
    private final Map<String, Instant> invisibleUntil = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 生产侧
    // ------------------------------------------------------------------

    public String publish(String topic, String tag, String body) {
        return publish(topic, tag, body, UUID.randomUUID().toString());
    }

    public String publish(String topic, String tag, String body, String messageId) {
        MockMessage message = new MockMessage(messageId, topic, tag, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pending.computeIfAbsent(topic, k -> new ArrayDeque<>()).addLast(message);
        log.debug("mock 消息已入队: id={} topic={} tag={}", messageId, topic, tag);
        return messageId;
    }

    // ------------------------------------------------------------------
    // 消费侧
    // ------------------------------------------------------------------

    public synchronized List<MockMessage> receive(String topic, String tag, int maxNum, Duration invisible) {
        sweepExpired();

        Deque<MockMessage> queue = pending.computeIfAbsent(topic, k -> new ArrayDeque<>());
        List<MockMessage> picked = new ArrayList<>();
        Deque<MockMessage> rest = new ArrayDeque<>();

        MockMessage m;
        while ((m = queue.pollFirst()) != null) {
            if (picked.size() < maxNum && matches(m, tag)) {
                picked.add(m);
            } else {
                rest.addLast(m);
            }
        }
        queue.addAll(rest);

        Instant expiry = Instant.now().plus(invisible);
        for (MockMessage p : picked) {
            p.markDelivered();
            inflight.put(p.messageId(), p);
            invisibleUntil.put(p.messageId(), expiry);
        }
        return picked;
    }

    public void ack(MockMessage message) {
        String id = message.messageId();
        if (inflight.remove(id) != null) {
            invisibleUntil.remove(id);
            log.debug("mock ACK: id={}", id);
        } else {
            log.warn("mock ACK 收到未知或已确认的消息: id={}", id);
        }
    }

    public void changeInvisible(MockMessage message, Duration duration) {
        if (inflight.containsKey(message.messageId())) {
            invisibleUntil.put(message.messageId(), Instant.now().plus(duration));
        }
    }

    /** 把过期的在途消息放回队列，模拟 MQ 的重新投递。 */
    private void sweepExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, Instant> e : invisibleUntil.entrySet()) {
            if (e.getValue().isAfter(now)) {
                continue;
            }
            String id = e.getKey();
            MockMessage m = inflight.remove(id);
            invisibleUntil.remove(id);
            if (m != null) {
                pending.computeIfAbsent(m.topic(), k -> new ArrayDeque<>()).addLast(m);
                log.debug("mock 消息不可见期已过，重新入队: id={} attempt={}", id, m.deliveryAttempt());
            }
        }
    }

    private static boolean matches(MockMessage m, String tag) {
        return tag == null || tag.isBlank() || "*".equals(tag) || tag.equals(m.tag());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    public int pendingCount(String topic) {
        Deque<MockMessage> q = pending.get(topic);
        return q == null ? 0 : q.size();
    }

    public int inflightCount() {
        return inflight.size();
    }

    public synchronized void reset() {
        pending.clear();
        inflight.clear();
        invisibleUntil.clear();
    }
}
