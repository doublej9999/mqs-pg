package com.mqspg.config.dto;

import java.time.OffsetDateTime;

/**
 * 版本草稿。
 *
 * <p>{@code version} 可能为 {@code null}：路由刚建好、还没存过草稿时，
 * 服务端会基于 ACTIVE 版本（也没有，则用空结构）合成一份**未持久化**的编辑视图，
 * 让页面永远有东西可编辑，而不必先 POST 一次。
 *
 * <p>{@code content} 是纯 Java 结构（Map / List），不是 Jackson 2 的 JsonNode。
 */
public record DraftDto(
        Long routeId,
        Integer version,
        String status,
        Object content,
        String jslt,
        String mappingStrategy,
        int mappingCount,
        String changeNote,
        OffsetDateTime updatedAt,
        boolean persisted) {
}
