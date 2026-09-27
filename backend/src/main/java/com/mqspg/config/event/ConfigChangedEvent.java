package com.mqspg.config.event;

/**
 * 路由配置发生变化（激活 / 回滚 / 停用）。
 *
 * <p>该事件在事务内发布，但由 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费，
 * 保证内存注册表只在数据库变更真正提交后才刷新。
 *
 * @param routeId 发生变化的路由
 */
public record ConfigChangedEvent(long routeId) {
}
