package com.mqspg.mqs.spi;

import java.time.Duration;

/**
 * 一个消费者的创建参数。
 *
 * @param routeId           cfg_route.id
 * @param topic             MQ Topic
 * @param tag               MQ Tag
 * @param group             消费组
 * @param pullSize          单次拉取条数
 * @param invisibleDuration 不可见时长，必须大于预期批处理耗时
 */
public record ConsumerSpec(
        long routeId,
        String topic,
        String tag,
        String group,
        int pullSize,
        Duration invisibleDuration) {
}
