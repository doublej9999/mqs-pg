package com.mqspg.common.model;

import java.util.List;

/**
 * 路由的**不可变**配置快照，运行时的唯一事实来源（ADR-06 / PRD §18）。
 *
 * <p>已绑定到某条消息的 {@code RouteConfig} 实例在其批次处理完成前不会被替换或回收，
 * 以此保证 PRD §19「历史版本不可被运行中的消息动态替换」。
 *
 * @param routeId         cfg_route.id
 * @param version         配置版本号
 * @param target          目标表引用
 * @param mappingStrategy 自动映射策略
 * @param jslt            JSLT 脚本，可为 null
 * @param mappings        字段映射列表
 */
public record RouteConfig(
        long routeId,
        int version,
        TargetRef target,
        MappingStrategy mappingStrategy,
        String jslt,
        List<MappingDef> mappings) {

    public RouteConfig {
        mappings = List.copyOf(mappings);
    }

    public boolean hasJslt() {
        return jslt != null && !jslt.isBlank();
    }

    public String updateTimeField() {
        return target.updateTimeField();
    }

    public List<String> upsertKeys() {
        return target.upsertKeys();
    }
}
