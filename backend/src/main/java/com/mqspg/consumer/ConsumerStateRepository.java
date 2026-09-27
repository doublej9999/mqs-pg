package com.mqspg.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mqspg.config.entity.RtConsumerState;
import com.mqspg.config.mapper.RtConsumerStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 消费状态的持久化与状态迁移（tech-design §6.1）。
 *
 * <p>状态机：{@code PAUSED → RUNNING → (PG 故障) PAUSED → RECOVERING → RUNNING}。
 * 状态写库是为了让 Console 能回答「这个路由为什么不动了」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerStateRepository {

    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String RECOVERING = "RECOVERING";
    public static final String ERROR = "ERROR";

    private final RtConsumerStateMapper mapper;

    public void markRunning(long routeId, int bindVersion) {
        update(routeId, RUNNING, null, bindVersion, false);
    }

    public void markPaused(long routeId, String reason, int bindVersion) {
        update(routeId, PAUSED, reason, bindVersion, false);
    }

    public void markRecovering(long routeId, String reason, int bindVersion) {
        update(routeId, RECOVERING, reason, bindVersion, false);
    }

    public void markError(long routeId, String reason, int bindVersion) {
        update(routeId, ERROR, reason, bindVersion, true);
    }

    /**
     * 刷新写入成功时间。
     *
     * <p>刻意不改 {@code status} 与 {@code bind_version}：
     * 一次成功不代表消费已恢复，也不该覆盖运维显式设置的暂停状态。
     */
    public void markSuccess(long routeId) {
        RtConsumerState existing = mapper.selectById(routeId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            RtConsumerState s = new RtConsumerState();
            s.setRouteId(routeId);
            s.setStatus(RUNNING);
            s.setLastSuccessAt(now);
            s.setUpdatedAt(now);
            mapper.insert(s);
            return;
        }
        existing.setLastSuccessAt(now);
        existing.setUpdatedAt(now);
        mapper.updateById(existing);
    }

    private void update(long routeId, String status, String reason, int bindVersion, boolean isError) {
        RtConsumerState existing = mapper.selectById(routeId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            RtConsumerState s = new RtConsumerState();
            s.setRouteId(routeId);
            s.setStatus(status);
            s.setReason(reason);
            s.setBindVersion(bindVersion);
            s.setUpdatedAt(now);
            if (isError) {
                s.setLastErrorAt(now);
            }
            mapper.insert(s);
            return;
        }
        // 显式 set(null)：updateById 默认忽略 null 字段（FieldStrategy.NOT_NULL），
        // 会让「恢复运行」后仍挂着上一次的暂停原因 —— 页面上就会出现
        // 状态 RUNNING 却写着「路由已停用」的自相矛盾。
        LambdaUpdateWrapper<RtConsumerState> w = Wrappers.<RtConsumerState>lambdaUpdate()
                .eq(RtConsumerState::getRouteId, routeId)
                .set(RtConsumerState::getStatus, status)
                .set(RtConsumerState::getReason, reason)
                .set(RtConsumerState::getBindVersion, bindVersion)
                .set(RtConsumerState::getUpdatedAt, now);
        if (isError) {
            w.set(RtConsumerState::getLastErrorAt, now);
        }
        mapper.update(null, w);
    }
}
