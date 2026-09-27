package com.mqspg.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.model.MappingDef;
import com.mqspg.common.model.TargetRef;
import com.mqspg.common.model.TransformSpec;
import com.mqspg.common.persistence.JsonNodes;
import com.mqspg.config.dto.ValidationIssueDto;
import com.mqspg.config.dto.ValidationResultDto;
import com.mqspg.config.entity.CfgRoute;
import com.mqspg.config.entity.CfgTarget;
import com.mqspg.config.registry.RouteConfigAssembler;
import com.mqspg.transform.ExpressionEvaluator;
import com.mqspg.transform.JsonPaths;
import com.mqspg.writer.metadata.ColumnMeta;
import com.mqspg.writer.metadata.TargetMetadataReader;
import com.mqspg.writer.metadata.TargetTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 配置发布前的静态校验（tech-design §12.5 的静态部分）。
 *
 * <p><b>与 §12.5 的差异</b>：设计里还包含「真实事务 + ROLLBACK」的端到端 Dry Run，
 * 那需要一条样例报文。本类只做**不需要样例数据**的检查 —— 表/列是否存在、
 * Upsert Key 有没有唯一索引、NOT NULL 列有没有来源、表达式能不能解析。
 * 这些覆盖了配置错误的大多数，且可以只凭配置本身判定。
 *
 * <p><b>为什么一次性返回全部问题</b>：配置错误往往是成片的。目标表名写错时
 * 每一条映射都会报「列不存在」，逐条报会让人提交十几次才改完。
 *
 * <p><b>宁漏不误</b>：校验器误报比不报更糟 —— 它会拦住本来正确的配置，
 * 而使用者只能靠改配置去绕过，最后学会的是不信任校验。所以凡是
 * 无法确定是配置问题的，一律降级为 WARN（见 {@link #validateExpression}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigValidator {

    /** {@code TypeConverter} 的 switch 分支全集。 */
    private static final Set<String> KNOWN_TRANSFORMS = Set.of(
            "string", "text", "varchar", "char",
            "long", "bigint", "int8",
            "integer", "int", "int4",
            "short", "int2",
            "decimal", "numeric", "bigdecimal",
            "double", "float8", "float", "real", "float4",
            "boolean", "bool",
            "timestamp", "datetime", "timestamptz", "date",
            "enum");

    private static final Set<String> KNOWN_STRATEGIES = Set.of("EXACT", "CAMEL_TO_SNAKE");

    /**
     * 探针文档：空对象。
     *
     * <p>用它跑一遍表达式即等价于语法检查 —— 取值缺失时 {@code numeric(null)} 返回 0、
     * {@code text(null)} 返回空串，都不会抛异常，只有真正的语法错误（未闭合字符串、
     * 意外记号、多余内容）和未知函数才会失败。
     */
    private static final ObjectNode EMPTY_OBJECT = JsonNodeFactory.instance.objectNode();

    /**
     * 这些消息与文档内容**无关**，出现即说明表达式本身写错了，可以直接拦下。
     *
     * <p>其余失败一律降级为 WARN：最典型的是 {@code a / b} 在空文档上求值成
     * {@code 0 / 0} → ArithmeticException。那是探针的副作用，不是配置错误。
     */
    private static final List<String> CONTEXT_FREE_EXPRESSION_ERRORS = List.of(
            "表达式语法错误", "未闭合", "意外的记号", "末尾有多余内容", "未知的表达式函数");

    private final TargetMetadataReader metadataReader;
    private final JsonPaths jsonPaths;
    private final ExpressionEvaluator expressionEvaluator;
    private final RouteConfigAssembler assembler;

    public ValidationResultDto validate(CfgRoute route, CfgTarget target, JsonNode content) {
        List<ValidationIssueDto> issues = new ArrayList<>();

        if (route == null) {
            issues.add(ValidationIssueDto.error("route", "路由不存在"));
            return result(issues);
        }
        if (isBlank(route.getTopic())) {
            issues.add(ValidationIssueDto.error("topic", "Topic 不能为空"));
        }
        if (target == null) {
            issues.add(ValidationIssueDto.error("target", "目标表定义不存在或未选择"));
            return result(issues);
        }
        if (content == null || content.isNull()) {
            issues.add(ValidationIssueDto.error("content", "配置内容为空，无法发布"));
            return result(issues);
        }

        validateStrategy(content, issues);

        List<MappingDef> mappings = assembler.mappings(content);
        if (mappings.isEmpty()) {
            issues.add(ValidationIssueDto.error("mappings", "至少需要一条字段映射"));
        }

        // 后续所有检查都依赖目标表结构，读不到就直接返回
        TargetRef ref = assembler.toTargetRef(target);
        TargetTableMeta meta;
        try {
            meta = metadataReader.get(ref);
        } catch (RuntimeException e) {
            issues.add(ValidationIssueDto.error("target",
                    "读取目标表 %s 结构失败: %s".formatted(ref.qualifiedTable(), rootMessage(e))));
            return result(issues);
        }

        validateMappings(mappings, ref, meta, issues);
        validateUpsertKeys(ref, meta, issues);
        validateUpdateTimeField(ref, meta, issues);
        validateNotNullCoverage(mappings, meta, issues);

        return result(issues);
    }

    // ------------------------------------------------------------------

    private void validateStrategy(JsonNode content, List<ValidationIssueDto> issues) {
        // 不能用 MappingStrategy.from：它对未知值静默回落 EXACT，
        // 那样拼错策略名永远不会被发现
        String raw = JsonNodes.text(content, "mappingStrategy");
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (!KNOWN_STRATEGIES.contains(raw.trim().toUpperCase(Locale.ROOT))) {
            issues.add(ValidationIssueDto.error("mappingStrategy",
                    "未知的映射策略: %s（可选 %s）".formatted(raw, KNOWN_STRATEGIES)));
        }
    }

    private void validateMappings(List<MappingDef> mappings, TargetRef ref,
                                  TargetTableMeta meta, List<ValidationIssueDto> issues) {
        Set<String> seen = new LinkedHashSet<>();
        for (MappingDef m : mappings) {
            if (isBlank(m.target())) {
                issues.add(ValidationIssueDto.error("mapping", "目标列名不能为空"));
                continue;
            }
            String field = "mapping." + m.target();

            if (!meta.hasColumn(m.target())) {
                issues.add(ValidationIssueDto.error(field,
                        "目标表 %s 不存在列 %s".formatted(ref.qualifiedTable(), m.target())));
                // 列都不存在，后面针对该列的检查没有意义
                continue;
            }
            if (!seen.add(m.target())) {
                issues.add(ValidationIssueDto.warn(field, "同一目标列被映射多次，只有最后一条会生效"));
            }
            validateMappingBody(m, field, issues);
        }
    }

    private void validateMappingBody(MappingDef m, String field, List<ValidationIssueDto> issues) {
        if (!producesValue(m)) {
            issues.add(ValidationIssueDto.error(field,
                    "没有 source / constant / expression / defaultValue，该列永远取不到值"));
        }

        TransformSpec t = m.transform();
        if (t != null && !isBlank(t.type())) {
            String type = t.type().toLowerCase(Locale.ROOT);
            if (!KNOWN_TRANSFORMS.contains(type)) {
                issues.add(ValidationIssueDto.error(field + ".transform",
                        "未知的转换类型: " + t.type()));
            }
            if (t.isEnum() && (t.enumMapping() == null || t.enumMapping().isEmpty())) {
                issues.add(ValidationIssueDto.error(field + ".transform",
                        "enum 转换必须给出 mapping（否则任何取值都会失败）"));
            }
            if (t.isTimestamp() && !isBlank(t.zone())) {
                try {
                    ZoneId.of(t.zone());
                } catch (RuntimeException e) {
                    issues.add(ValidationIssueDto.error(field + ".transform",
                            "非法时区: " + t.zone()));
                }
            }
        }

        if (hasText(m.source())) {
            try {
                jsonPaths.validate(m.source());
            } catch (ProcessingException e) {
                issues.add(ValidationIssueDto.error(field + ".source", e.getMessage()));
            }
        }

        if (m.hasExpression()) {
            validateExpression(m.expression(), field + ".expression", issues);
        }
    }

    private void validateExpression(String expression, String field, List<ValidationIssueDto> issues) {
        try {
            expressionEvaluator.evaluate(expression, EMPTY_OBJECT);
        } catch (ProcessingException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            boolean contextFree = CONTEXT_FREE_EXPRESSION_ERRORS.stream().anyMatch(message::contains);
            if (contextFree) {
                issues.add(ValidationIssueDto.error(field, message));
            } else {
                // 多半是 0/0 之类的探针副作用，不拦发布
                issues.add(ValidationIssueDto.warn(field,
                        "表达式在空报文上求值失败（可能只是缺少字段，不一定是配置错误）: " + message));
            }
        } catch (RuntimeException e) {
            issues.add(ValidationIssueDto.warn(field,
                    "表达式预检未能完成: " + rootMessage(e)));
        }
    }

    private void validateUpsertKeys(TargetRef ref, TargetTableMeta meta, List<ValidationIssueDto> issues) {
        List<String> keys = ref.upsertKeys();
        if (keys == null || keys.isEmpty()) {
            issues.add(ValidationIssueDto.error("upsertKeys", "Upsert Key 不能为空"));
            return;
        }
        boolean allExist = true;
        for (String k : keys) {
            if (!meta.hasColumn(k)) {
                issues.add(ValidationIssueDto.error("upsertKeys",
                        "目标表不存在 Upsert Key 列: " + k));
                allExist = false;
            }
        }
        // 唯一索引检查只在列都存在时做：否则会叠加一条无意义的报错
        if (allExist && !meta.hasUniqueIndexOn(keys)) {
            issues.add(ValidationIssueDto.error("upsertKeys",
                    "Upsert Key %s 上没有唯一索引或主键 —— MERGE 的 ON 条件依赖它保证一个目标行最多匹配一条源数据"
                            .formatted(keys)));
        }
    }

    private void validateUpdateTimeField(TargetRef ref, TargetTableMeta meta, List<ValidationIssueDto> issues) {
        String field = ref.updateTimeField();
        if (isBlank(field)) {
            issues.add(ValidationIssueDto.error("updateTimeField", "单调守卫字段不能为空"));
            return;
        }
        if (!meta.hasColumn(field)) {
            issues.add(ValidationIssueDto.error("updateTimeField",
                    "目标表 %s 不存在列 %s".formatted(ref.qualifiedTable(), field)));
        }
    }

    /**
     * PRD §16：{@code NOT NULL} 且无数据库默认值的列，必须由配置提供值。
     *
     * <p>不满足时**发布失败**，而不是等到消息落库才报约束冲突 ——
     * 那时坏消息已经进了重试表，排查成本高得多。
     */
    private void validateNotNullCoverage(List<MappingDef> mappings, TargetTableMeta meta,
                                         List<ValidationIssueDto> issues) {
        Set<String> covered = new LinkedHashSet<>();
        for (MappingDef m : mappings) {
            if (producesValue(m) && m.target() != null) {
                covered.add(m.target());
            }
        }
        for (ColumnMeta c : meta.columns().values()) {
            if (c.requiresValue() && !covered.contains(c.name())) {
                issues.add(ValidationIssueDto.error("target." + c.name(),
                        "列 %s 为 NOT NULL 且无数据库默认值，必须提供 source / constant / expression / defaultValue"
                                .formatted(c.name())));
            }
        }
    }

    // ------------------------------------------------------------------

    /** 该映射是否**可能**产出非 null 值。 */
    private static boolean producesValue(MappingDef m) {
        return hasText(m.source()) || m.hasConstant() || m.hasExpression() || m.hasDefault();
    }

    private static ValidationResultDto result(List<ValidationIssueDto> issues) {
        List<ValidationIssueDto> copy = List.copyOf(issues);
        boolean hasError = copy.stream().anyMatch(i -> "ERROR".equals(i.severity()));
        return new ValidationResultDto(!hasError, copy);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
