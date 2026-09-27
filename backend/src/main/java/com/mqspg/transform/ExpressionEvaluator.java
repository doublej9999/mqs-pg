package com.mqspg.transform;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 字段表达式求值（PRD §13 的 {@code expression} 来源）。
 *
 * <p>抽象为接口是为了避免锁死实现：tech-design 附录 C-02 尚在评估 Aviator（LGPL），
 * 当前默认实现 {@code SimpleExpressionEvaluator} 是自研的受限表达式解析器，
 * 无第三方依赖、无许可证风险。
 */
public interface ExpressionEvaluator {

    /**
     * @param expression 表达式文本，如 {@code amount * 100}
     * @param context    消息体（已完成 JSLT 变换后的 JSON）
     * @return 求值结果（BigDecimal / String / Boolean / null）
     */
    Object evaluate(String expression, JsonNode context);
}
