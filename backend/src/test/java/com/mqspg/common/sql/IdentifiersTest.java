package com.mqspg.common.sql;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;

/**
 * 标识符白名单测试（里程碑 I4）。
 *
 * <p>{@code schema} / {@code table} 会被**直接拼进 MERGE 的 SQL 文本**，
 * 不是参数绑定。写入侧原来只做 trim/lowercase，{@code table_name} 甚至只 trim ——
 * 也就是 {@code public;DROP} 这种值能存进 {@code cfg_target}。
 */
@DisplayName("标识符白名单")
class IdentifiersTest {

    @Test
    @DisplayName("正常标识符通过")
    void acceptsNormalIdentifiers() {
        assertEquals("public", Identifiers.requireValid("public", "schema"));
        assertEquals("my_table2", Identifiers.requireValid("my_table2", "表名"));
        // PG 允许 $ 出现在非首字符
        assertEquals("t$a", Identifiers.requireValid("t$a", "表名"));
        // 前后空白被裁掉
        assertEquals("orders", Identifiers.requireValid("  orders  ", "表名"));
    }

    /**
     * 用 {@code assertAll} 而不是 {@code @ParameterizedTest}：本仓库的用例计数口径是
     * 「一个 {@code @Test} 算一个用例」（见 README），参数化会打破这个口径。
     * {@code assertAll} 同样能一次跑完所有坏值并逐条报错。
     */
    @Test
    @DisplayName("危险或不合法的标识符被拒")
    void rejectsDangerousIdentifiers() {
        List<String> bad = List.of(
                "public;DROP",          // 语句拼接
                "public; DROP TABLE x", // 带空格的分号
                "my table",             // 空格
                "\"my table\"",         // 带引号（PG 允许，本项目不支持）
                "a'b",                  // 单引号
                "123abc",               // 数字开头：会被当成数字字面量
                "$abc",                 // $ 开头：与占位符混淆
                "public.table",         // 点号：会把 schema.table 变成三段
                "a--comment",           // 注释
                "a/*c*/");              // 块注释
        assertAll(bad.stream().map(v -> () -> {
            ProcessingException e = assertThrows(ProcessingException.class,
                    () -> Identifiers.requireValid(v, "表名"), "应拒绝: " + v);
            assertEquals(ErrorCode.INVALID_INPUT, e.getCode(), "错误码: " + v);
            assertFalse(Identifiers.isValid(v), "isValid 应为 false: " + v);
        }));
    }

    @Test
    @DisplayName("空值与超长被拒")
    void rejectsBlankAndTooLong() {
        assertThrows(ProcessingException.class, () -> Identifiers.requireValid(null, "表名"));
        assertThrows(ProcessingException.class, () -> Identifiers.requireValid("   ", "表名"));

        // PG 上限 NAMEDATALEN(64) - 1 = 63
        String ok = "a".repeat(63);
        assertEquals(ok, Identifiers.requireValid(ok, "表名"));
        String tooLong = "a".repeat(64);
        assertThrows(ProcessingException.class, () -> Identifiers.requireValid(tooLong, "表名"));
        assertTrue(tooLong.length() > 63);
    }
}
