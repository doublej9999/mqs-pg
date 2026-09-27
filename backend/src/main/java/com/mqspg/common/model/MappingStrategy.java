package com.mqspg.common.model;

/** 自动字段映射策略（PRD §14 / §15）。 */
public enum MappingStrategy {

    /** 同名精确匹配（默认）。 */
    EXACT,

    /** camelCase → snake_case，可选启用。 */
    CAMEL_TO_SNAKE;

    public static MappingStrategy from(String s) {
        if (s == null || s.isBlank()) {
            return EXACT;
        }
        return switch (s.trim().toUpperCase()) {
            case "CAMEL_TO_SNAKE", "CAMELTOSNAKE" -> CAMEL_TO_SNAKE;
            default -> EXACT;
        };
    }
}
