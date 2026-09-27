package com.mqspg.config.event;

/**
 * 路由配置发生变化（新增 / 修改 / 激活 / 回滚 / 停用 / 删除）。
 *
 * <p>该事件在事务内发布，但由 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费，
 * 保证内存注册表与消费者只在数据库变更真正提交后才调整。
 *
 * <p>{@code change} 是刻意带上的：监听器据此决定「启动 / 重建 / 停止」消费者。
 * 若只传 routeId，监听器就只能回查数据库去猜意图 —— 而对「删除」而言，
 * 提交之后这一行已经不存在了，回查必然得到 null，无法与「没有 ACTIVE 版本」区分。
 *
 * @param routeId 发生变化的路由
 * @param change  变更类型
 */
public record ConfigChangedEvent(long routeId, Change change) {

    public enum Change {

        /**
         * 路由本身被新增或修改（name / topic / tag / target 可能已变）。
         *
         * <p>消费者按 Topic+Tag 订阅，改了这两者必须重建，
         * 否则旧消费者会继续拉取已被替换掉的 Topic。
         */
        ROUTE_UPSERTED,

        /** 激活了新版本：消费者不变，但它读到的 ACTIVE 配置已换。 */
        VERSION_ACTIVATED,

        /** 路由被停用或删除：消费者必须停止。 */
        ROUTE_REMOVED
    }

    public static ConfigChangedEvent routeUpserted(long routeId) {
        return new ConfigChangedEvent(routeId, Change.ROUTE_UPSERTED);
    }

    public static ConfigChangedEvent versionActivated(long routeId) {
        return new ConfigChangedEvent(routeId, Change.VERSION_ACTIVATED);
    }

    public static ConfigChangedEvent routeRemoved(long routeId) {
        return new ConfigChangedEvent(routeId, Change.ROUTE_REMOVED);
    }
}
