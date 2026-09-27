package com.mqspg.config.registry;

import com.mqspg.config.event.ConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在配置变更事务**提交之后**刷新内存注册表。
 *
 * <p>为什么不用普通 {@code @EventListener}：那样会在事务提交前刷新，
 * 消费线程可能读到最终被回滚的配置，也可能读到尚未提交的版本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigRefreshListener {

    private final ConfigRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfigChanged(ConfigChangedEvent event) {
        try {
            registry.refresh(event.routeId());
        } catch (Exception e) {
            // 刷新失败不应影响已提交的配置变更；下一次 refreshAll 会纠正
            log.error("刷新配置注册表失败: routeId={}", event.routeId(), e);
        }
    }
}
