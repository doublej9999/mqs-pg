package com.mqspg.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.persistence.JsonbTypeHandler;

import java.util.List;
import java.util.Map;

/**
 * 目标数据源连接池参数（Hikari 语义）。
 *
 * <p><b>为什么要做成强类型</b>：原来是裸 {@code Object poolConfig}，直接
 * {@code JsonNodes.toJsonNode} 落库，直到运行期 {@code applyPoolConfig} 才逐键匹配 ——
 * 键拼错（{@code poolconfig} / {@code maxPoolSize}）只会打一行
 * {@code log.warn("忽略未知的连接池参数")} 就过去了。配置看起来生效了，其实什么都没设。
 * 参数校验也拖到运行期：{@code maximumPoolSize: "abc"} 在创建时静默通过。
 *
 * <p><b>全部字段可空</b>：只有用户显式给出的键才会被 {@code applyPoolConfig} 应用，
 * 未给出的沿用 Hikari 默认值。「不设」和「设为 null」是同一件事。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} 是默认行为，这里显式写出来
 * 是为了表明<b>拼错的键必须报错</b>，而不是被忽略 —— 这正是本类要解决的问题。
 * 未知键由 Jackson 抛出 {@code UnrecognizedPropertyException}，Web 层统一转 400。
 *
 * @param maximumPoolSize   最大连接数（≥ 1）
 * @param minimumIdle       最小空闲连接数（≥ 0，且 ≤ maximumPoolSize）
 * @param connectionTimeout 获取连接超时，毫秒（≥ 250）
 * @param idleTimeout       空闲连接回收，毫秒（≥ 10000，或 0 表示不回收）
 * @param maxLifetime       连接最长存活，毫秒（≥ 30000，或 0 表示不限）
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PoolConfig(
        Integer maximumPoolSize,
        Integer minimumIdle,
        Long connectionTimeout,
        Long idleTimeout,
        Long maxLifetime) {

    /** 允许的键名，用于错误提示。 */
    public static final List<String> ALLOWED_KEYS = List.of(
            "maximumPoolSize", "minimumIdle", "connectionTimeout", "idleTimeout", "maxLifetime");

    public PoolConfig {
        // 校验放在构造器里：反序列化后立刻失败，错误能精确定位到是哪个键
        if (maximumPoolSize != null && maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize 必须 ≥ 1，当前为 " + maximumPoolSize);
        }
        if (minimumIdle != null && minimumIdle < 0) {
            throw new IllegalArgumentException("minimumIdle 必须 ≥ 0，当前为 " + minimumIdle);
        }
        if (maximumPoolSize != null && minimumIdle != null && minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "minimumIdle(%d) 不能大于 maximumPoolSize(%d)".formatted(minimumIdle, maximumPoolSize));
        }
        // Hikari 的下限是 250ms，低于它会直接抛异常；提前拦下来给个能看懂的提示
        if (connectionTimeout != null && connectionTimeout < 250) {
            throw new IllegalArgumentException("connectionTimeout 必须 ≥ 250 毫秒，当前为 " + connectionTimeout);
        }
        if (idleTimeout != null && idleTimeout != 0 && idleTimeout < 10_000) {
            throw new IllegalArgumentException(
                    "idleTimeout 必须 ≥ 10000 毫秒（或 0 表示不回收），当前为 " + idleTimeout);
        }
        if (maxLifetime != null && maxLifetime != 0 && maxLifetime < 30_000) {
            throw new IllegalArgumentException(
                    "maxLifetime 必须 ≥ 30000 毫秒（或 0 表示不限），当前为 " + maxLifetime);
        }
    }

    /** 是否一个参数都没设。用来自动落 {@code {}} 而不是 null。 */
    public boolean isEmpty() {
        return maximumPoolSize == null && minimumIdle == null
                && connectionTimeout == null && idleTimeout == null && maxLifetime == null;
    }

    /**
     * 把 Web 层传来的任意 JSON 值解析成本 record，**不合法就抛 400**。
     *
     * <p>为什么不用 Jackson 3 直接反序列化到本类：未知键会在反序列化阶段失败，
     * 落到 {@code HttpMessageNotReadableException} 的通用分支，提示只剩
     * 「请求体不是合法 JSON」—— 而真正的原因往往只是键名拼错
     * （{@code poolconfig} / {@code maxPoolSize}）。这里显式解析，才能列出
     * 允许的键名，用户照着改就行。
     *
     * <p>用 Jackson 2 的 mapper 而不是 Jackson 3：入参已经是纯 Java 结构
     * （Jackson 3 把 JSON 解析成了 Map），这里只是 Map → record 的转换，
     * 与 {@code JsonNodes} 同一个 mapper，语义一致。
     *
     * @param raw 请求体里的 {@code poolConfig} 字段，可为 null
     * @return 解析结果；{@code raw == null} 时返回 {@code null}（表示「不改」）
     * @throws com.mqspg.common.error.ProcessingException 非对象、未知键、类型不符、取值越界
     */
    public static PoolConfig parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new ProcessingException(ErrorCode.INVALID_INPUT,
                    "poolConfig 必须是 JSON 对象，可选键：" + ALLOWED_KEYS);
        }
        // 先自己查一遍未知键：Jackson 的原始报错带类名与方法名，对使用者没有意义
        for (Object key : ((Map<?, ?>) raw).keySet()) {
            if (!ALLOWED_KEYS.contains(String.valueOf(key))) {
                throw new ProcessingException(ErrorCode.INVALID_INPUT,
                        "连接池参数含未知的键「%s」，可选键：%s".formatted(key, ALLOWED_KEYS));
            }
        }
        try {
            return JsonbTypeHandler.mapper().convertValue(raw, PoolConfig.class);
        } catch (IllegalArgumentException e) {
            // 类型不符（"abc" 给 Integer）或构造器里的取值范围校验，都是用户能自己改的
            throw new ProcessingException(ErrorCode.INVALID_INPUT,
                    "连接池参数不合法: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
