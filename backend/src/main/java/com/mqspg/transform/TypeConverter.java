package com.mqspg.transform;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.TransformSpec;
import com.mqspg.common.persistence.JsonNodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 字段级类型转换（tech-design §8.4）。
 *
 * <p>设计取向：**严格而非宽容**。数值一律经 {@link BigDecimal} 再
 * {@code longValueExact()}，宁可报 {@code TYPE_CONVERSION_ERROR} 也不静默截断
 * （{@code 1.9 → 1} 这类错误一旦落库极难发现）。
 */
@Slf4j
@Component
public class TypeConverter {

    public Object convert(TransformSpec spec, Object raw, String column) {
        Object value = JsonNodes.unwrap(raw);
        if (value == null) {
            return null;
        }
        String type = (spec == null || spec.type() == null) ? "string" : spec.type().toLowerCase();
        try {
            return switch (type) {
                case "string", "text", "varchar", "char" -> asString(value);
                case "long", "bigint", "int8" -> asLong(value);
                case "integer", "int", "int4" -> asInteger(value);
                case "short", "int2" -> asShort(value);
                case "decimal", "numeric", "bigdecimal" -> asDecimal(value);
                case "double", "float8" -> asDouble(value);
                case "float", "real", "float4" -> asFloat(value);
                case "boolean", "bool" -> asBoolean(value);
                case "timestamp", "datetime", "timestamptz" -> asTimestamp(spec, value);
                case "date" -> asDate(spec, value);
                case "enum" -> asEnum(spec, value, column);
                default -> throw new ProcessingException(ErrorCode.TYPE_CONVERSION_ERROR,
                        "字段 %s 使用了未知的转换类型: %s".formatted(column, type));
            };
        } catch (ProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.TYPE_CONVERSION_ERROR,
                    "字段 %s 转换为 %s 失败: 值=%s (%s)".formatted(column, type, value, e.getMessage()), e);
        }
    }

    // ------------------------------------------------------------------

    private static String asString(Object v) {
        return v instanceof String s ? s : String.valueOf(v);
    }

    private static Long asLong(Object v) {
        return decimalOf(v).longValueExact();
    }

    private static Integer asInteger(Object v) {
        return decimalOf(v).intValueExact();
    }

    private static Short asShort(Object v) {
        return decimalOf(v).shortValueExact();
    }

    private static BigDecimal asDecimal(Object v) {
        return decimalOf(v);
    }

    private static Double asDouble(Object v) {
        return decimalOf(v).doubleValue();
    }

    private static Float asFloat(Object v) {
        return decimalOf(v).floatValue();
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("y")) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase("false") || s.equals("0") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("n")) {
            return Boolean.FALSE;
        }
        throw new ProcessingException(ErrorCode.TYPE_CONVERSION_ERROR, "无法解析为布尔值: " + s);
    }

    private static BigDecimal decimalOf(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ProcessingException(ErrorCode.TYPE_CONVERSION_ERROR, "无法解析为数值: " + s, e);
        }
    }

    // ------------------------------------------------------------------

    private static OffsetDateTime asTimestamp(TransformSpec spec, Object v) {
        if (v instanceof OffsetDateTime odt) {
            return odt;
        }
        if (v instanceof Instant i) {
            return i.atOffset(ZoneOffset.UTC);
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(zoneOf(spec)).toOffsetDateTime();
        }
        // 必须先于 java.util.Date 判断：java.sql.Date 是它的子类，
        // 而 java.sql.Date.toInstant() 会抛 UnsupportedOperationException
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate().atStartOfDay(zoneOf(spec)).toOffsetDateTime();
        }
        if (v instanceof Date d) {
            return d.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue()).atOffset(ZoneOffset.UTC);
        }

        String s = v.toString().trim();
        ZoneId zone = zoneOf(spec);
        String pattern = spec == null ? null : spec.pattern();

        if (pattern != null && !pattern.isBlank()) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
            // 带偏移量/时区的模式（含 X / Z / V）→ 直接得到 OffsetDateTime
            try {
                return OffsetDateTime.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // 落到「无偏移量的本地时间」分支
            }
            try {
                return LocalDateTime.parse(s, fmt).atZone(zone).toOffsetDateTime();
            } catch (DateTimeParseException e) {
                throw new ProcessingException(ErrorCode.DATETIME_FORMAT_ERROR,
                        "时间 '%s' 不匹配模式 '%s'（时区 %s）".formatted(s, pattern, zone), e);
            }
        }

        // 无 pattern：按 ISO-8601 依次尝试
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return Instant.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return LocalDateTime.parse(s).atZone(zone).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new ProcessingException(ErrorCode.DATETIME_FORMAT_ERROR,
                    "无法解析时间: " + s, e);
        }
    }

    private static java.sql.Date asDate(TransformSpec spec, Object v) {
        OffsetDateTime odt = asTimestamp(spec, v);
        LocalDate local = odt.atZoneSameInstant(zoneOf(spec)).toLocalDate();
        return java.sql.Date.valueOf(local);
    }

    private static ZoneId zoneOf(TransformSpec spec) {
        if (spec == null || spec.zone() == null || spec.zone().isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(spec.zone());
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.DATETIME_FORMAT_ERROR,
                    "非法时区: " + spec.zone(), e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 枚举映射。未命中映射表时抛 {@code ENUM_MAPPING_ERROR} ——
     * 不静默回落默认值，因为「上游新增了一个枚举值」是必须被发现的信号。
     * 若业务上允许未知值，应在配置里显式给出 defaultValue。
     */
    private static Object asEnum(TransformSpec spec, Object v, String column) {
        String key = v.toString().trim();
        String mapped = spec.enumMapping().get(key);
        if (mapped == null) {
            throw new ProcessingException(ErrorCode.ENUM_MAPPING_ERROR,
                    "字段 %s 的枚举值 '%s' 不在映射表中: %s".formatted(column, key, spec.enumMapping().keySet()));
        }
        return literal(mapped);
    }

    /** 把映射目标字面量还原为合适的 Java 类型（默认数字优先，符合枚举映射习惯）。 */
    private static Object literal(String s) {
        String t = s.trim();
        if (t.matches("-?\\d+")) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                return new BigDecimal(t);
            }
        }
        if (t.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (t.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (t.equalsIgnoreCase("null")) {
            return null;
        }
        return t;
    }
}
