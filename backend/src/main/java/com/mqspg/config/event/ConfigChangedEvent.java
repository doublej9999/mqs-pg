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
 * @param version 与变更相关的版本号；只有 {@link Change#VERSION_DELETED} 会用到，其余为 null
 */
public record ConfigChangedEvent(long routeId, Change change, Integer version) {

    /** 不带版本号的变更（绝大多数情况）。 */
    public ConfigChangedEvent(long routeId, Change change) {
        this(routeId, change, null);
    }

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
        ROUTE_REMOVED,

        /**
         * 某个**非生效**版本被删除：注册表必须驱逐它的内存快照。
         *
         * <p>不驱逐的话，这条版本在内存里还活着，重试重放时而有配置、时而没有 ——
         * 表现为「同一条消息重试三次有时成功有时 CONFIG_GONE」，极难排查。
         * 消费者不受影响（被删的不是 ACTIVE 版本，{@code deleteVersion} 已经拦住了）。
         */
        VERSION_DELETED
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

    public static ConfigChangedEvent versionDeleted(long routeId, int version) {
        return new ConfigChangedEvent(routeId, Change.VERSION_DELETED, version);
    }
}
