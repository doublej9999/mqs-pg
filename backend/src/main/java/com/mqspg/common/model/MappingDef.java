package com.mqspg.common.model;

/**
 * 一条字段映射规则（tech-design §8.4）。
 *
 * <p>取值来源优先级：{@code constant} &gt; {@code expression} &gt; {@code source(JSONPath)}。
 * 三者皆缺省或取值为 null 时，回落 {@code defaultValue}。
 *
 * @param target       目标列名
 * @param source       源 JSONPath，如 {@code $.user.id}
 * @param constant     常量值，如 {@code ORDER_SYSTEM}
 * @param expression   表达式，如 {@code amount * 100}
 * @param transform    类型转换声明
 * @param required     是否必填（缺失即 MISSING_REQUIRED_FIELD）
 * @param defaultValue 缺省值
 */
public record MappingDef(
        String target,
        String source,
        String constant,
        String expression,
        TransformSpec transform,
        boolean required,
        String defaultValue) {

    /** 是否由常量提供值（无需源字段）。 */
    public boolean hasConstant() {
        return constant != null;
    }

    public boolean hasExpression() {
        return expression != null && !expression.isBlank();
    }

    public boolean hasDefault() {
        return defaultValue != null;
    }
}
