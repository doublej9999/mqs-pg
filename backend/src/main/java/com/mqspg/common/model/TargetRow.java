package com.mqspg.common.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一行目标数据：列名 → 值，**保持插入顺序**。
 *
 * <p>顺序很重要：SQL 生成时按此顺序绑定参数与列名。
 */
public final class TargetRow {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public TargetRow put(String column, Object value) {
        values.put(column, value);
        return this;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public boolean contains(String column) {
        return values.containsKey(column);
    }

    /** 只读视图，保持插入顺序。 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public int size() {
        return values.size();
    }

    /** 按 Upsert Key 列顺序取键值。 */
    public List<Object> keyValues(List<String> keys) {
        return keys.stream().map(this::get).toList();
    }

    /** 取时间值并统一为 {@link Instant}；无法识别时返回 null。 */
    public Instant getInstant(String column) {
        return toInstant(values.get(column));
    }

    public static Instant toInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception ignored) {
                // 继续尝试 ISO-8601 瞬时格式
            }
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
