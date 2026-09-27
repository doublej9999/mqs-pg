package com.mqspg.common.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson 2 与 Jackson 3 之间的边界适配。
 *
 * <p><b>背景</b>：Spring Boot 4 的 Web 层默认使用 Jackson 3（{@code tools.jackson}），
 * 而 JSLT、jayway json-path 以及本项目的 JSONB 类型处理器基于 Jackson 2
 * （{@code com.fasterxml.jackson}）。
 *
 * <p>如果把 Jackson 2 的 {@link JsonNode} 直接放进 REST 响应 DTO，Jackson 3 无法识别，
 * 会退化成 bean 内省，序列化出 {@code nodeType} / {@code bigDecimal} 之类的属性，
 * 而不是真正的 JSON —— 表现为接口返回一坨无意义字段。
 *
 * <p><b>规则</b>：Jackson 2 类型只允许出现在持久化层与转换引擎内部；
 * 跨越 Web 边界前必须经本类转为纯 Java 结构（Map / List / String / Number / Boolean）。
 */
public final class JsonNodes {

    private static final ObjectMapper MAPPER = JsonbTypeHandler.mapper();

    private JsonNodes() {
    }

    /** Jackson 2 JsonNode → 纯 Java 结构（LinkedHashMap / ArrayList / 标量）。 */
    public static Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    /** 取文本字段；缺失或为 null 时返回 {@code null}。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** 取文本字段，缺失时返回默认值。 */
    public static String text(JsonNode node, String field, String defaultValue) {
        String v = text(node, field);
        return v == null ? defaultValue : v;
    }
}
