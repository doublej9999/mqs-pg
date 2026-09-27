package com.mqspg.config.dto;

import java.time.OffsetDateTime;

/** 配置版本列表项。 */
public record VersionSummaryDto(
        Integer version,
        String status,
        String changeNote,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        boolean active) {
}
