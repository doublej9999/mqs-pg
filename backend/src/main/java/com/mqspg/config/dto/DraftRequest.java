package com.mqspg.config.dto;

/**
 * 草稿保存请求。
 *
 * <p>{@code content} 期望形如
 * {@code {"mappingStrategy":"EXACT","jslt":null,"mappings":[...]}}，
 * 由服务端转为 Jackson 2 的 JsonNode 后写入 JSONB 列。
 */
public record DraftRequest(
        Object content,
        String changeNote) {
}
