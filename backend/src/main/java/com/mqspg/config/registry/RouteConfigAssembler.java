package com.mqspg.config.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.mqspg.common.model.MappingDef;
import com.mqspg.common.model.MappingStrategy;
import com.mqspg.common.model.RouteConfig;
import com.mqspg.common.model.TargetRef;
import com.mqspg.common.model.TransformSpec;
import com.mqspg.config.entity.CfgTarget;
import com.mqspg.config.entity.CfgVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 {@code cfg_version.content}（JSONB 快照）+ {@code cfg_target} 装配为 {@link RouteConfig}。
 *
 * <p>快照结构见 tech-design §12.2：
 * <pre>
 * {
 *   "mappingStrategy": "EXACT",
 *   "jslt": null,
 *   "mappings": [ { "target": "...", "source": "...", "transform": {...}, ... } ]
 * }
 * </pre>
 */
@Component
public class RouteConfigAssembler {

    public TargetRef toTargetRef(CfgTarget t) {
        return new TargetRef(
                t.getId(),
                t.getDatasourceId(),
                t.getSchemaName(),
                t.getTableName(),
                readStringList(t.getUpsertKeys()),
                t.getUpdateTimeField());
    }

    public RouteConfig assemble(CfgVersion version, CfgTarget target) {
        JsonNode content = version.getContent();
        if (content == null || content.isNull()) {
            throw new IllegalStateException(
                    "配置版本内容为空: routeId=%d version=%d".formatted(version.getRouteId(), version.getVersion()));
        }
        return new RouteConfig(
                version.getRouteId(),
                version.getVersion(),
                toTargetRef(target),
                MappingStrategy.from(text(content, "mappingStrategy")),
                text(content, "jslt"),
                parseMappings(content.get("mappings")));
    }

    /**
     * 解析快照中的映射列表。
     *
     * <p>公开出来是给**发布校验**用的：草稿还没有对应的 {@code CfgVersion} 行，
     * 无法走 {@link #assemble}，但校验又必须看到解析后的 {@link MappingDef}。
     * 与其在校验器里再写一份解析逻辑，不如共用这一处 —— 内容格式只在这一个类里定义。
     */
    public List<MappingDef> mappings(JsonNode content) {
        if (content == null || content.isNull()) {
            return List.of();
        }
        return parseMappings(content.get("mappings"));
    }

    // ------------------------------------------------------------------

    private List<MappingDef> parseMappings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<MappingDef> out = new ArrayList<>(array.size());
        for (JsonNode m : array) {
            out.add(new MappingDef(
                    text(m, "target"),
                    text(m, "source"),
                    text(m, "constant"),
                    text(m, "expression"),
                    parseTransform(m.get("transform")),
                    m.path("required").asBoolean(false),
                    text(m, "defaultValue")));
        }
        return List.copyOf(out);
    }

    private TransformSpec parseTransform(JsonNode tr) {
        if (tr == null || tr.isNull()) {
            return TransformSpec.of("string");
        }
        // 允许简写：transform: decimal
        if (tr.isTextual()) {
            return TransformSpec.of(tr.asText());
        }
        Map<String, String> enumMapping = new LinkedHashMap<>();
        JsonNode em = tr.get("mapping");
        if (em != null && em.isObject()) {
            // 用 fieldNames() 而非 properties()/fields()，避免 Jackson 版本间的 API 差异
            Iterator<String> it = em.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                enumMapping.put(key, em.get(key).asText());
            }
        }
        return new TransformSpec(text(tr, "type"), Map.copyOf(enumMapping),
                text(tr, "pattern"), text(tr, "zone"));
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of("id");
        }
        if (!node.isArray()) {
            return List.of(node.asText());
        }
        List<String> out = new ArrayList<>(node.size());
        node.forEach(e -> out.add(e.asText()));
        // 空数组无意义，回落到默认 Upsert Key
        return out.isEmpty() ? List.of("id") : List.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
