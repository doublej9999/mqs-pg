package com.mqspg.common.sql;

import java.util.regex.Pattern;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;

/**
 * PG 标识符（schema / table / column）的白名单校验。
 *
 * <p><b>为什么需要它</b>：{@code schema} 与 {@code table} 会被**直接拼进 MERGE 的
 * SQL 文本**（{@code MergeSqlBuilder} 里所有 {@code %s.%s} 的位置），不是参数绑定。
 * 而写入侧原来只做 {@code trim()} / lowercase，{@code table_name} 甚至只 trim ——
 * 也就是说 {@code createTarget} 可以存下一个含分号或空格的表名，只要该表真实存在
 * （元数据查询走参数绑定，不会拦住名字本身），MERGE 的 SQL 文本就被污染了。
 *
 * <p>注入面确实被「列必须真实存在」收窄了一部分，但 {@code schema.table} 这个位置
 * 没有那层保护 —— {@code IntrospectionService.requireIdentifying} 的注释早就
 * 说明了这个位置为什么危险，可它只覆盖探查入参，不覆盖落库路径。
 *
 * <p><b>取舍</b>：PG 允许 {@code "my table"} 这种带引号的标识符，本项目**不支持**。
 * 白名单是 {@code [A-Za-z_][A-Za-z0-9_$]*} 且长度 ≤ 63（PG 的 NAMEDATALEN - 1）。
 * 拒绝一个合法但怪异的表名，比放行一个能改 SQL 结构的表名划算得多。
 */
public final class Identifiers {

    /** PG 标识符上限：NAMEDATALEN(64) - 1。 */
    private static final int MAX_LENGTH = 63;

    /**
     * 不加引号也合法的标识符。
     *
     * <p>刻意不允许首字符是数字（{@code 123abc} 在 SQL 里会被当成数字字面量），
     * 也不允许 {@code $} 开头（PG 允许，但极易与占位符混淆）。
     */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    private Identifiers() {
    }

    /**
     * 校验并返回原值。
     *
     * @param value 待校验的标识符
     * @param what  人类可读的字段名，用于拼错误信息（如「schema」「表名」）
     * @throws ProcessingException {@link ErrorCode#INVALID_INPUT}，附带具体原因
     */
    public static String requireValid(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ProcessingException(ErrorCode.INVALID_INPUT, what + "不能为空");
        }
        String v = value.trim();
        if (v.length() > MAX_LENGTH) {
            throw new ProcessingException(ErrorCode.INVALID_INPUT,
                    "%s 超长: %d 个字符，PostgreSQL 上限为 %d".formatted(what, v.length(), MAX_LENGTH));
        }
        if (!VALID.matcher(v).matches()) {
            // 不回显原值全文：它可能很长或是精心构造的注入串，回显没有排障价值
            throw new ProcessingException(ErrorCode.INVALID_INPUT,
                    ("%s 只能是字母、数字、下划线或 $，且不能以数字或 $ 开头"
                            + "（本项目不支持带引号的标识符）").formatted(what));
        }
        return v;
    }

    /** 只判断不抛异常，供防守式检查使用。 */
    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        return v.length() <= MAX_LENGTH && VALID.matcher(v).matches();
    }
}
