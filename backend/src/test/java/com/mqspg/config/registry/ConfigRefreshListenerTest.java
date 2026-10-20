package com.mqspg.config.registry;

import com.mqspg.config.event.ConfigChangedEvent;
import com.mqspg.consumer.ConsumerManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AFTER_COMMIT 监听器的失败隔离测试（里程碑 H6）。
 *
 * <p>两件事必须成立：
 * ① <b>刷新注册表失败时不要再动消费者</b> —— 否则会拉起一个拿不到配置、
 *    注定空转的线程（监听器自己的 javadoc 就写着这个顺序理由）；
 * ② <b>任何一步失败都不向上抛</b> —— 配置变更已经提交，监听器抛异常
 *    只会污染调用方的事务收尾，纠正工作交给 H1 的周期性对账。
 */
@DisplayName("配置变更监听器的失败隔离")
class ConfigRefreshListenerTest {

    private final ConfigRegistry registry = mock(ConfigRegistry.class);
    private final ConsumerManager consumerManager = mock(ConsumerManager.class);
    private final ConfigRefreshListener listener = new ConfigRefreshListener(registry, consumerManager);

    @Test
    @DisplayName("注册表刷新失败时跳过消费者调整")
    void refreshFailureSkipsConsumerChanges() {
        doThrow(new IllegalStateException("db 抖动")).when(registry).refresh(42L);

        // 不该抛出去
        listener.onConfigChanged(ConfigChangedEvent.versionActivated(42L));

        // 关键：没有拿着旧注册表去启动消费者
        verify(consumerManager, never()).ensureStarted(anyLong());
    }

    @Test
    @DisplayName("消费者调整失败被单独吞掉，不影响其它变更")
    void consumerFailureIsContained() {
        doThrow(new IllegalStateException("MQ 订阅失败"))
                .when(consumerManager).restart(anyLong());

        listener.onConfigChanged(ConfigChangedEvent.routeUpserted(7L));

        // 注册表那一步仍然做了
        verify(registry).refresh(7L);
        verify(consumerManager).restart(7L);
    }

    @Test
    @DisplayName("删除版本只驱逐快照，不动消费者")
    void versionDeletedOnlyEvicts() {
        listener.onConfigChanged(ConfigChangedEvent.versionDeleted(9L, 3));

        verify(registry).evict(9L, 3);
        // 删一个非生效版本不该停掉或重启消费者
        verify(consumerManager, never()).stop(anyLong(), anyString());
        verify(consumerManager, never()).restart(anyLong());
        verify(consumerManager, never()).ensureStarted(anyLong());
        // 也不该整表刷新
        verify(registry, never()).refresh(anyLong());
    }

    @Test
    @DisplayName("驱逐失败同样不向上抛")
    void evictFailureIsContained() {
        doThrow(new IllegalStateException("boom")).when(registry).evict(anyLong(), any(Integer.class));

        listener.onConfigChanged(ConfigChangedEvent.versionDeleted(9L, 3));
        // 走到这里就说明没抛 —— 配置变更已提交，不能因为收尾失败而炸
    }
}
