package com.mqspg.config.dto;

import java.util.List;

/**
 * 路由详情：路由 + 目标表 + 当前 ACTIVE 配置快照 + 版本列表。
 *
 * <p>{@code content} 声明为 {@link Object} 而非 Jackson 2 的 {@code JsonNode}：
 * Boot 4 的 Web 层用 Jackson 3，无法序列化 Jackson 2 的 JsonNode。
 * 见 {@code com.mqspg.common.persistence.JsonNodes} 的说明。
 */
public record RouteDetailDto(
        RouteSummaryDto route,
        TargetDto target,
        Object content,
        String jslt,
        String mappingStrategy,
        int mappingCount,
        List<VersionSummaryDto> versions) {
}
