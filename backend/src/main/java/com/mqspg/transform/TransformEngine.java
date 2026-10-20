package com.mqspg.transform;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.MappingDef;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRow;
import com.mqspg.common.persistence.JsonbTypeHandler;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息体 → 目标行的转换（tech-design §8）。
 *
 * <p>流水线：{@code 原始字节 → JSON 解析 → (可选) JSLT 结构变换 → 逐字段取值 → 类型转换 → 目标行}
 *
 * <p><b>取值优先级</b>：{@code constant} &gt; {@code expression} &gt; {@code source(JSONPath)}，
 * 三者皆空或取到 null 时回落 {@code defaultValue}；仍为 null 且 {@code required} 则报错。
 *
 * <p><b>版本绑定</b>：传入的 {@link RouteConfig} 即消息接收时刻绑定的快照（PRD §18），
 * 本类不做任何配置查找，因此天然满足 PRD §19。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformEngine {

    private final JsonPaths jsonPaths;
    private final TypeConverter typeConverter;
    private final ExpressionEvaluator expressionEvaluator;

    /**
     * JSLT 编译缓存（J7）：编译开销远大于执行，命中即免去整段解析。
     *
     * <p><b>为什么必须有上限</b>：key 是脚本全文。原实现注释写着「配置版本数有限，
     * 无需淘汰」—— 这话只在「每个版本都发布上线」时成立。而草稿校验
     * （{@code ConfigValidator#validateJslt}）也会走这里，于是**每改一次草稿的
     * jslt 就新增一项**，一个下午的调脚本就能攒出成百上千条永不释放的编译产物。
     *
     * <p>用手写 LRU（{@code LinkedHashMap} + {@code removeEldestEntry}）而不是
     * Caffeine：这里只有一处用法、一个上限数字，为它引入新依赖不划算。
     * 并发上直接 {@code synchronized} —— 命中是一次 O(1) 查找，而未命中的代价是
     * 编译一整段脚本，锁的竞争完全淹没在设计本就昂贵的路径里。
     */
    private static final int JSLT_CACHE_LIMIT = 512;

    /** 缓存上限，供测试与诊断读取。 */
    public static int jsltCacheLimit() {
        return JSLT_CACHE_LIMIT;
    }

    private final Map<String, Expression> jsltCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Expression> eldest) {
            return size() > JSLT_CACHE_LIMIT;
        }
    };

    /** 当前缓存条数，供诊断/测试。 */
    public synchronized int jsltCacheSize() {
        return jsltCache.size();
    }

    private synchronized Expression compileJslt(String script) {
        return jsltCache.computeIfAbsent(script, Parser::compileString);
    }

    /**
     * @param config 消息接收时刻绑定的配置快照
     * @param body   原始消息体
     * @throws ProcessingException 解析 / 变换 / 取值 / 转换 / 校验失败
     */
    public TargetRow transform(RouteConfig config, byte[] body) {
        JsonNode document = parse(body);
        if (config.hasJslt()) {
            document = applyJslt(config.jslt(), document);
        }

        TargetRow row = new TargetRow();
        for (MappingDef mapping : config.mappings()) {
            Object value = resolve(mapping, document);
            if (value == null) {
                value = mapping.hasDefault() ? mapping.defaultValue() : null;
            }
            if (value == null) {
                if (mapping.required()) {
                    throw new ProcessingException(ErrorCode.MISSING_REQUIRED_FIELD,
                            "必填字段缺失: 目标列 %s (来源 %s)".formatted(
                                    mapping.target(), describeSource(mapping)));
                }
                // 可选且无默认值：整列不写入，交由 PG 的 DEFAULT / NULL 处理
                continue;
            }
            row.put(mapping.target(), typeConverter.convert(mapping.transform(), value, mapping.target()));
        }

        if (row.size() == 0) {
            throw new ProcessingException(ErrorCode.MISSING_REQUIRED_FIELD,
                    "转换结果为空，配置未产出任何列: routeId=%d version=%d"
                            .formatted(config.routeId(), config.version()));
        }
        return row;
    }

    // ------------------------------------------------------------------

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new ProcessingException(ErrorCode.JSON_PARSE_ERROR, "消息体为空");
        }
        try {
            return JsonbTypeHandler.mapper().readTree(body);
        } catch (java.io.IOException e) {
            throw new ProcessingException(ErrorCode.JSON_PARSE_ERROR,
                    "消息体不是合法 JSON: " + abbreviate(e.getMessage()), e);
        }
    }

    private JsonNode applyJslt(String script, JsonNode input) {
        Expression compiled;
        try {
            compiled = compileJslt(script);
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.JSLT_ERROR, "JSLT 脚本无法编译: " + abbreviate(e.getMessage()), e);
        }
        try {
            return compiled.apply(input);
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.JSLT_ERROR, "JSLT 执行失败: " + abbreviate(e.getMessage()), e);
        }
    }

    /** 按优先级取值；返回 null 表示「未取到」。 */
    private Object resolve(MappingDef mapping, JsonNode document) {
        if (mapping.hasConstant()) {
            return mapping.constant();
        }
        if (mapping.hasExpression()) {
            return expressionEvaluator.evaluate(mapping.expression(), document);
        }
        if (mapping.source() != null && !mapping.source().isBlank()) {
            return jsonPaths.read(document, mapping.source());
        }
        return null;
    }

    private static String describeSource(MappingDef m) {
        if (m.hasConstant()) {
            return "constant";
        }
        if (m.hasExpression()) {
            return "expression:" + m.expression();
        }
        return m.source() == null ? "(未声明来源)" : m.source();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "(无详情)";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /** 供配置发布时做 JSLT 语法预校验。 */
    public void validateJslt(String script) {
        if (script == null || script.isBlank()) {
            return;
        }
        try {
            compileJslt(script);
        } catch (RuntimeException e) {
            throw new ProcessingException(ErrorCode.JSLT_ERROR, "JSLT 脚本无法编译: " + e.getMessage(), e);
        }
    }

    /** 供序列化调试用。 */
    static String toJson(JsonNode node) {
        try {
            return JsonbTypeHandler.mapper().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return String.valueOf(node);
        }
    }
}
