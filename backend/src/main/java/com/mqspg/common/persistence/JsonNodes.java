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

    /**
     * 纯 Java 结构 → Jackson 2 JsonNode。
     *
     * <p>与 {@link #toPlain} 方向相反，用于把 Web 层（Jackson 3）反序列化出来的
     * Map / List 写回 JSONB 列。请求 DTO 同样不能声明 {@code JsonNode}：
     * Jackson 3 不认识 Jackson 2 的类型，反序列化会失败。
     */
    public static JsonNode toJsonNode(Object plain) {
        if (plain == null) {
            return null;
        }
        return MAPPER.valueToTree(plain);
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

    /**
     * 把 JSONPath / JSLT 的取值结果解包为标准 Java 值。
     *
     * <p>JSONPath 返回 {@code JsonNode} 还是 Java 原生类型取决于 provider，
     * 转换前统一走此方法，避免下游到处 {@code instanceof}。
     * 容器类型（对象/数组）序列化为 JSON 字符串 —— 目标列都是标量。
     */
    public static Object unwrap(Object raw) {
        if (!(raw instanceof JsonNode n)) {
            return raw;
        }
        if (n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isFloatingPointNumber()) {
            return n.decimalValue();
        }
        if (n.isContainerNode()) {
            return n.toString();
        }
        return n.asText();
    }
}
