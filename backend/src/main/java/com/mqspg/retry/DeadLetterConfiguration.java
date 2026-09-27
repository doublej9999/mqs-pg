package com.mqspg.retry;

import com.mqspg.mqs.spi.FailureMeta;
import com.mqspg.mqs.spi.MqsDeadLetterSink;
import com.mqspg.mqs.spi.MqsMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V1 的 DLQ 落点。
 *
 * <p>V1 **不依赖 MQ 平台的原生死信队列**：重试次数耗尽的终态是数据库里的一行
 * {@code rt_error_record(is_final = true)} 加上 {@code rt_retry_task.status = 'DLQ'}。
 * 这样做的好处是死信与业务数据在同一个可查询的地方，控制台可以直接列出与重放。
 *
 * <p>{@link MqsDeadLetterSink} 这个 SPI 仍然保留：接入平台 MQS 时，
 * 如果对方有原生 DLQ（如 RocketMQ 的 %DLQ% 主题），只需注册一个自己的实现，
 * 本默认实现会被 {@code @ConditionalOnMissingBean} 让位。
 */
@Slf4j
@Configuration
public class DeadLetterConfiguration {

    @Bean
    @ConditionalOnMissingBean(MqsDeadLetterSink.class)
    public MqsDeadLetterSink databaseDeadLetterSink() {
        return (MqsMessage message, FailureMeta meta) -> log.error(
                "消息进入 DLQ（数据库留痕）: id={} topic={} tag={} stage={} code={} attempt={} reason={}",
                message.messageId(), message.topic(), message.tag(),
                meta.errorStage(), meta.errorCode(), meta.attempt(), meta.errorMessage());
    }
}
