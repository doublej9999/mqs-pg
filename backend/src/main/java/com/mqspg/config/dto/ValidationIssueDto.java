package com.mqspg.config.dto;

/**
 * 一条校验问题。
 *
 * @param severity {@code ERROR} 阻止发布，{@code WARN} 仅提示
 * @param field    出错位置，如 {@code target.amount} / {@code upsertKeys}，
 *                 页面据此把错误定位到具体那一行
 * @param message  人类可读的原因
 */
public record ValidationIssueDto(String severity, String field, String message) {

    public static ValidationIssueDto error(String field, String message) {
        return new ValidationIssueDto("ERROR", field, message);
    }

    public static ValidationIssueDto warn(String field, String message) {
        return new ValidationIssueDto("WARN", field, message);
    }
}
