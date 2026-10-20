package com.mqspg.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSLT 编译缓存上限测试（J7）。
 *
 * <p>纯单元测试，不起 Spring 容器：要验的是「缓存容量」这一个纯逻辑性质。
 * 通过公开的 {@link TransformEngine#validateJslt} 触发编译 —— 那正是草稿校验
 * 用的入口，也是这个缺陷的来源。
 */
@DisplayName("JSLT 编译缓存")
class JsltCacheTest {

    private static TransformEngine engine() {
        JsonPaths paths = new JsonPaths();
        return new TransformEngine(paths, new TypeConverter(),
                new com.mqspg.transform.expr.SimpleExpressionEvaluator(paths));
    }

    /**
     * 缓存必须有上限。
     *
     * <p>原实现是「不淘汰」的 ConcurrentHashMap，理由是「配置版本数有限」。
     * 但草稿校验（{@code ConfigValidator#validateJslt}）也走这个缓存 ——
     * 每改一次草稿的 jslt 就新增一项，一个下午调脚本就能攒出上千条
     * 永不释放的编译产物。
     */
    @Test
    @DisplayName("写入超过上限后，缓存条数停在上限不再增长")
    void cacheIsBounded() {
        TransformEngine engine = engine();
        int limit = TransformEngine.jsltCacheLimit();
        assertTrue(limit > 0 && limit <= 4096, "上限应是一个合理的小数字，实际 " + limit);

        // 写 limit + 200 个互不相同的脚本
        int total = limit + 200;
        for (int i = 0; i < total; i++) {
            engine.validateJslt("{ \"a\": .x + " + i + " }");
        }

        assertEquals(limit, engine.jsltCacheSize(),
                "缓存条数必须停在上限，而不是随脚本数线性增长");
    }

    @Test
    @DisplayName("同一条脚本重复校验只占一个位置")
    void sameScriptReusesEntry() {
        TransformEngine engine = engine();
        String script = "{ \"a\": .a }";

        engine.validateJslt(script);
        engine.validateJslt(script);

        assertEquals(1, engine.jsltCacheSize(), "同一脚本应只占一个缓存位置");
    }
}
