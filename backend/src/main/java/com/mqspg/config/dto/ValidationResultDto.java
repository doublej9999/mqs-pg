package com.mqspg.config.dto;

import java.util.List;

/**
 * 发布校验结果。
 *
 * <p>一次返回**所有**问题而非遇到第一个就中断：配置错误往往是成片的
 * （比如目标表写错，所有列都会报不存在），逐个报会让人反复提交十几次。
 */
public record ValidationResultDto(boolean valid, List<ValidationIssueDto> issues) {

    public static ValidationResultDto ok() {
        return new ValidationResultDto(true, List.of());
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> "ERROR".equals(i.severity()));
    }
}
