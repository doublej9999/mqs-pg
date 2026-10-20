package com.mqspg.config.registry;

import com.mqspg.config.event.DatasourceChangedEvent;
import com.mqspg.writer.datasource.TargetDataSourceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 目标数据源配置变更后，在**事务提交之后**丢弃旧的连接池。
 *
 * <p>为什么必须等提交：在事务内 {@code close()} 会让正在该池上执行 MERGE 的
 * 消费线程收到连接中断。消息不会丢（会被重投），但会产生一批本可避免的
 * {@code PG_CONNECTION_ERROR} 与闸门抖动；而且若事务随后回滚，
 * 池已销毁而配置其实没变，只能等下一次惰性重建。
 *
 * <p>与 {@link ConfigRefreshListener} 分开：那个监听器动的是「注册表 + 消费者」，
 * 这个动的是「连接池」。合成一个会让监听器长出「如果 id 是路由就…否则…」的分支。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceRefreshListener {

    private final TargetDataSourceRegistry dataSources;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDatasourceChanged(DatasourceChangedEvent event) {
        long id = event.datasourceId();
        try {
            dataSources.invalidate(id);
            log.info("数据源 {} 已变更，旧连接池已丢弃（下次使用按新参数重建）", id);
        } catch (Exception e) {
            // 关池失败不应影响已提交的配置变更：池是惰性重建的，
            // 最坏情况只是旧池多活一会儿，下次 invalidate 会再关一次。
            log.error("数据源变更后关闭旧连接池失败: datasourceId={}", id, e);
        }
    }
}
