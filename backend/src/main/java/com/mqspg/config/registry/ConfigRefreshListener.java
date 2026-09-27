package com.mqspg.config.registry;

import com.mqspg.config.event.ConfigChangedEvent;
import com.mqspg.consumer.ConsumerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在配置变更事务**提交之后**刷新内存注册表并调整消费者。
 *
 * <p>为什么不用普通 {@code @EventListener}：那样会在事务提交前刷新，
 * 消费线程可能读到最终被回滚的配置，也可能读到尚未提交的版本。
 *
 * <p>顺序很重要：**先刷注册表，再动消费者**。反过来的话，新建的消费者
 * 可能在注册表还没有该路由的 ACTIVE 快照时就跑了第一轮，
 * 那一轮会因为拿不到配置而空转（甚至被判为 CONFIG_MISSING）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigRefreshListener {

    private final ConfigRegistry registry;
    private final ConsumerManager consumerManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfigChanged(ConfigChangedEvent event) {
        long routeId = event.routeId();
        try {
            switch (event.change()) {
                case ROUTE_REMOVED -> {
                    // 删除后这一行已经不在库里，refresh 会把它从注册表摘掉
                    registry.refresh(routeId);
                    consumerManager.stop(routeId, "路由已停用或删除");
                }
                case ROUTE_UPSERTED -> {
                    registry.refresh(routeId);
                    // Topic/Tag/Target 可能已变，按新配置重建消费者
                    consumerManager.restart(routeId);
                }
                case VERSION_ACTIVATED -> {
                    registry.refresh(routeId);
                    consumerManager.ensureStarted(routeId);
                }
            }
        } catch (Exception e) {
            // 刷新失败不应影响已提交的配置变更；下一次 refreshAll 会纠正
            log.error("配置变更后处理失败: routeId={} change={}", routeId, event.change(), e);
        }
    }
}
