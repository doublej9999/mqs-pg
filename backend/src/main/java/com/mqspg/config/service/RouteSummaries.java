package com.mqspg.config.service;

import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.entity.CfgTarget;
import com.mqspg.config.entity.RtConsumerState;

/**
 * {@link RouteSummaryDto} 的装配（里程碑 C1）。
 *
 * <p>此前 {@code ConfigService} 与 {@code ConfigAdminService} 各有一份**逐字段相同**的
 * {@code toSummary}，且都在循环里按 route 查目标表与消费状态，构成 N+1。
 * 抽出单一实现后：调用方负责**批量**把关联实体取好，这里只做纯内存组装。
 *
 * <p>因此本类刻意不持有任何 Mapper —— 它拿到的都是已经查好的实体，
 * 从结构上杜绝再次引入 N+1。
 */
final class RouteSummaries {

    private RouteSummaries() {
    }

    static RouteSummaryDto build(CfgRoute route, CfgTarget target, RtConsumerState state) {
        return new RouteSummaryDto(
                route.getId(), route.getName(), route.getTopic(), route.getTag(),
                route.getTargetId(),
                target == null ? null : target.getSchemaName() + "." + target.getTableName(),
                route.getActiveVersion(), route.getStatus(),
                state == null ? null : state.getStatus(),
                state == null ? null : state.getReason());
    }
}
