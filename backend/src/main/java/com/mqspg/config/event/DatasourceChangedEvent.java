package com.mqspg.config.event;

/**
 * 目标数据源配置发生变化（修改 / 删除）。
 *
 * <p>与 {@link ConfigChangedEvent} 分开是因为监听器要动的东西不同：
 * 路由变化改的是「注册表 + 消费者」，数据源变化要丢掉的是**连接池**。
 * 混在一个事件里会让监听器长出「如果 id 是路由就…否则…」的分支。
 *
 * <p>同样在事务内发布、由 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费。
 * 这一点对本事件尤其重要：在事务提交前就 {@code close()} 旧池，
 * 会让正在该池上执行 MERGE 的消费线程收到连接中断 ——
 * 消息不会丢（会被重投），但会产生一批本可避免的 {@code PG_CONNECTION_ERROR}
 * 与闸门抖动；若事务随后回滚，池已销毁而配置没变，还得等下一次惰性重建。
 *
 * @param datasourceId 发生变化的数据源 id
 */
public record DatasourceChangedEvent(long datasourceId) {
}
