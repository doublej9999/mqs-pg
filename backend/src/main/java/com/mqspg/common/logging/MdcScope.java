package com.mqspg.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;

/**
 * MDC 关联字段作用域（里程碑 B3）。
 *
 * <p>此前日志靠字符串拼接 {@code route=... message=...}，采集器无法按字段索引。
 * 本类把关联字段放进 MDC，配合日志 pattern 输出为可检索的固定字段。
 *
 * <p><b>务必用 try-with-resources</b>：MDC 底层是 ThreadLocal。本项目启用了虚拟线程，
 * 载体线程会被复用；不清理会把上一条消息的 {@code messageId} 泄漏到无关日志，
 * 让"按 messageId 搜日志"搜出错误结果。
 *
 * <p>采用「保存旧值 → 设置 → 关闭时恢复」而非直接清空，以支持嵌套作用域
 * （例如批次级只设 routeId，逐条处置时再叠加 messageId）。
 */
public final class MdcScope implements AutoCloseable {

    public static final String ROUTE_ID = "routeId";
    public static final String MESSAGE_ID = "messageId";
    public static final String CONFIG_VERSION = "configVersion";

    private final Map<String, String> previous;

    private MdcScope(Map<String, String> values) {
        Map<String, String> prev = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            prev.put(e.getKey(), MDC.get(e.getKey()));
            put(e.getKey(), e.getValue());
        }
        this.previous = prev;
    }

    /**
     * routeId / messageId / configVersion 任一为 null 时该字段不写入（保持外层值）。
     *
     * <p>version 用 {@code Object} 而非重载：既接受 {@code Integer} 也接受 {@code String}，
     * 同时避免调用处传 {@code null} 时出现重载歧义。
     */
    public static MdcScope open(String routeId, String messageId, Object configVersion) {
        Map<String, String> values = new LinkedHashMap<>(4);
        values.put(ROUTE_ID, routeId);
        values.put(MESSAGE_ID, messageId);
        values.put(CONFIG_VERSION, configVersion == null ? null : String.valueOf(configVersion));
        return new MdcScope(values);
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @Override
    public void close() {
        previous.forEach(MdcScope::put);
    }
}
