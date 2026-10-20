package com.mqspg.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 目标表新建 / 修改请求。
 *
 * <p><b>长度上限照抄 DDL</b>：name/schema_name/table_name/update_time_field
 * 都是 VARCHAR(64)（name 是 VARCHAR(128)），原来没有长度校验，超长值直接撞
 * PG 的 SQLSTATE 22001。
 *
 * <p>{@code schemaName} / {@code updateTimeField} 在 DDL 里有 DEFAULT，
 * 因此允许为空（服务端 {@code defaultIfBlank} 兜底）；{@code tableName} 是 NOT NULL 且无默认，
 * 必须给值。{@code upsertKeys} 允许为空列表，服务端会补成 {@code ["id"]}。
 *
 * <p>标识符的**字符合法性**（是否含注入字符）不在这里检查 —— 那需要报出
 * 「只能是字母数字下划线」这种具体原因，放在 {@code Identifiers} 里做。
 */
public record TargetRequest(
        @NotBlank(message = "目标表名称不能为空")
        @Size(max = 128, message = "目标表名称最长 128 个字符")
        String name,

        @NotNull(message = "必须选择数据源")
        Long datasourceId,

        @Size(max = 64, message = "schema 最长 64 个字符")
        String schemaName,

        @NotBlank(message = "表名不能为空")
        @Size(max = 64, message = "表名最长 64 个字符")
        String tableName,

        List<String> upsertKeys,

        @Size(max = 64, message = "单调守卫字段最长 64 个字符")
        String updateTimeField) {
}
