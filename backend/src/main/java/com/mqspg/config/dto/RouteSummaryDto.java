package com.mqspg.config.dto;

/** 路由列表项。 */
public record RouteSummaryDto(
        Long id,
        String name,
        String topic,
        String tag,
        Long targetId,
        String targetTable,
        Integer activeVersion,
        String status,
        String consumerStatus,
        String consumerReason) {
}
