package com.mqspg.writer.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * localhost 主机名覆盖测试（L2 容器化时补）。
 *
 * <p>纯单元测试：只验证字符串层面「主机位」的替换，不建连接池。
 *
 * <p>为什么要单独测这件事：demo 种子数据把目标数据源写成
 * {@code jdbc:postgresql://localhost:5432/postgres}，后端进容器后
 * {@code localhost} 指向容器自己，连接必然被拒 —— 表现是路由永久停在
 * 「目标数据源不可用」，而这条链路原本没有任何容器化适配。
 */
@DisplayName("目标数据源 localhost 覆盖")
class LocalhostOverrideTest {

    @Test
    @DisplayName("只替换主机位，端口/库名/参数不动")
    void rewritesHostOnly() {
        assertEquals(
                "jdbc:postgresql://postgres:5432/postgres",
                TargetDataSourceRegistry.rewriteLocalhost(
                        "jdbc:postgresql://localhost:5432/postgres", "postgres"));

        // 带 currentSchema 参数时，参数部分必须原样保留
        assertEquals(
                "jdbc:postgresql://postgres:5432/postgres?currentSchema=mqs_pg,public",
                TargetDataSourceRegistry.rewriteLocalhost(
                        "jdbc:postgresql://localhost:5432/postgres?currentSchema=mqs_pg,public", "postgres"));

        // 127.0.0.1 同样处理
        assertEquals(
                "jdbc:postgresql://db.internal:6543/app",
                TargetDataSourceRegistry.rewriteLocalhost(
                        "jdbc:postgresql://127.0.0.1:6543/app", "db.internal"));
    }

    @Test
    @DisplayName("库名或参数里出现 localhost 字样时不被误伤")
    void doesNotTouchNonHostParts() {
        // 库名叫 localhost_db、参数里有 x=localhost —— 只有主机位该变
        assertEquals(
                "jdbc:postgresql://postgres:5432/localhost_db?x=localhost",
                TargetDataSourceRegistry.rewriteLocalhost(
                        "jdbc:postgresql://localhost:5432/localhost_db?x=localhost", "postgres"));
    }

    @Test
    @DisplayName("非 localhost 的地址原样返回；未配置覆盖时也不改动")
    void leavesEverythingElseAlone() {
        String real = "jdbc:postgresql://db.example.com:5432/app";
        assertEquals(real, TargetDataSourceRegistry.rewriteLocalhost(real, "postgres"));

        // 没有配置覆盖（空串）→ 行为与改造前完全一致
        String local = "jdbc:postgresql://localhost:5432/postgres";
        assertEquals(local, TargetDataSourceRegistry.rewriteLocalhost(local, ""));
        assertEquals(local, TargetDataSourceRegistry.rewriteLocalhost(local, null));
        assertNull(TargetDataSourceRegistry.rewriteLocalhost(null, "postgres"));
    }
}
