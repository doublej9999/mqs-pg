package com.mqspg.config.registry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mqspg.config.event.ConfigChangedEvent;
import com.mqspg.consumer.ConsumerManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
        // 按步骤分别捕获：原来整段共用一个 try，日志只有 routeId 与 change，
        // 看到「配置变更后处理失败」完全不知道是刷新注册表挂了还是消费者操作挂了。
        // 这两件事的排查方向完全不同（前者查配置装配，后者查 MQ 订阅），必须分开记。
        switch (event.change()) {
            case ROUTE_REMOVED ->
                // 删除后这一行已经不在库里，refresh 会把它从注册表摘掉
                    refreshThen(routeId, event.change(),
                            () -> consumerManager.stop(routeId, "路由已停用或删除"));
            case ROUTE_UPSERTED ->
                // Topic/Tag/Target 可能已变，按新配置重建消费者
                    refreshThen(routeId, event.change(), () -> consumerManager.restart(routeId));
            case VERSION_ACTIVATED ->
                    refreshThen(routeId, event.change(), () -> consumerManager.ensureStarted(routeId));
            case VERSION_DELETED -> {
                // 只驱逐快照，不动消费者：被删的是非生效版本
                // （生效版本在 deleteVersion 里就被拦住了）
                try {
                    registry.evict(routeId, event.version());
                } catch (Exception e) {
                    log.error("配置变更后驱逐版本快照失败: routeId={} version={}",
                            routeId, event.version(), e);
                }
            }
        }
    }

    /**
     * 先刷注册表、再执行消费者动作，两步分别捕获。
     *
     * <p>顺序不能反：新建的消费者可能在注册表还没有该路由 ACTIVE 快照时就跑第一轮，
     * 那一轮会因为拿不到配置而空转。所以这里把 refresh 单独包起来 ——
     * 它失败就**不再**动消费者，否则会拉起一个注定空转的线程。
     */
    private void refreshThen(long routeId, ConfigChangedEvent.Change change, Runnable after) {
        try {
            registry.refresh(routeId);
        } catch (Exception e) {
            // 刷新失败不应影响已提交的配置变更；H1 的周期性对账会纠正
            log.error("配置变更后刷新注册表失败: routeId={} change={}（已跳过消费者调整）",
                    routeId, change, e);
            return;
        }
        try {
            after.run();
        } catch (Exception e) {
            log.error("配置变更后调整消费者失败: routeId={} change={}", routeId, change, e);
        }
    }
}
