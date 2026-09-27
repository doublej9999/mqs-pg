package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.consumer.ConsumerManager;
import com.mqspg.consumer.RouteConsumer;
import com.mqspg.mqs.mock.MockMqsBroker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock MQ 的投递入口。
 *
 * <p>仅当 {@code mqs-pg.mqs.vendor=mock} 时注册。它的价值在于让整条链路
 * （投递 → 消费 → 转换 → 折叠 → MERGE → ACK）可以用 curl 走通一遍，
 * 而不必等平台 MQS 就位。
 */
@RestController
@RequestMapping("/api/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mqs-pg.mqs.vendor", havingValue = "mock", matchIfMissing = true)
public class MockController {

    private final MockMqsBroker broker;
    private final ConsumerManager consumerManager;

    /**
     * 投递一条消息。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/mock/publish \
     *   -H 'Content-Type: application/json' \
     *   -d '{"topic":"order-topic","tag":"order.updated","body":"{\"id\":1,...}"}'
     * </pre>
     *
     * <p>{@code body} 是 JSON 字符串而非 JSON 对象：这样调用方可以直接粘贴
     * 原始报文的字符串形式，避免被 Spring 重新序列化而改变字段顺序或精度。
     */
    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publish(@RequestBody MockPublishRequest request) {
        String messageId = broker.publish(request.topic(), request.tag(), request.body());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("topic", request.topic());
        data.put("tag", request.tag());
        data.put("pending", broker.pendingCount(request.topic()));
        return ApiResponse.ok(data);
    }

    /** 观察消费侧计数与暂停状态。 */
    @GetMapping("/status/{routeId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable long routeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        RouteConsumer c = consumerManager.consumerOf(routeId);
        data.put("routeId", routeId);
        if (c == null) {
            data.put("started", false);
            return ApiResponse.ok(data);
        }
        data.put("started", true);
        data.put("paused", c.isPaused());
        data.put("pauseReason", c.pauseReason());
        data.put("received", c.receivedCount());
        data.put("acked", c.ackedCount());
        data.put("notAcked", c.noAckCount());
        data.put("batches", c.batchCount());
        return ApiResponse.ok(data);
    }

    /** 请求体。{@code body} 为字符串形式的 JSON 报文。 */
    public record MockPublishRequest(String topic, String tag, String body) {
    }
}
