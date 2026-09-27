package com.mqspg.common.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Upsert Key 的值元组，用于批次内折叠与 RETURNING 结果归属判定。
 *
 * <p>不可变；equals/hashCode 由 record + List 提供。
 */
public record KeyTuple(List<Object> values) {

    public KeyTuple {
        values = List.copyOf(values);
    }

    public static KeyTuple of(List<Object> values) {
        return new KeyTuple(values);
    }

    /**
     * 规范化构造：消除数值类型的「同值不同形」问题。
     *
     * <p>{@code BigDecimal} 的 equals 对 scale 敏感（{@code 1.0 != 1.00}），
     * 若直接用于 Key 会导致折叠失效、同一 Key 被拆成两条。故统一去尾零后转
     * {@code BigDecimal}，并对整数型收敛为 {@code Long}。
     */
    public static KeyTuple ofNormalized(List<Object> rawValues) {
        return new KeyTuple(rawValues.stream().map(KeyTuple::normalize).toList());
    }

    private static Object normalize(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd.stripTrailingZeros();
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Short s) {
            return s.longValue();
        }
        return v;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
