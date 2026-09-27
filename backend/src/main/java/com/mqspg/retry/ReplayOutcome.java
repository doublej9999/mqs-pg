package com.mqspg.retry;

import com.mqspg.common.model.MergeAction;

/**
 * 一次重试重放的结果分类。
 *
 * <p>分类决定「是否消耗重试次数」，这是重试策略里最关键的分叉：
 * <ul>
 *   <li>消息内容本身的问题（{@code ROW_FAILURE} / {@code DATA_ERROR}）→ **消耗**次数，
 *       次数耗尽进 DLQ；</li>
 *   <li>系统性问题（{@code CONFIG_MISSING} / {@code PG_FAILURE}）→ **不消耗**次数。
 *       配置没发布、数据库连不上，重试一百次也不会变好，
 *       把次数耗光只会把一条本来能成功的消息推进 DLQ。</li>
 * </ul>
 */
public record ReplayOutcome(
        Kind kind,
        MergeAction action,
        String errorCode,
        String errorMessage,
        Throwable cause) {

    public enum Kind {
        /** 写入成功（含被单调守卫跳过 —— 说明有更新的数据胜出，同样算成功）。 */
        SUCCESS,
        /** 该行被 PG 拒绝（数据级）。 */
        ROW_FAILURE,
        /** 转换阶段就失败（数据级）。 */
        DATA_ERROR,
        /** 绑定版本的配置已不可用。 */
        CONFIG_MISSING,
        /** PG 级故障。 */
        PG_FAILURE
    }

    /** 是否应消耗一次重试机会。 */
    public boolean consumesAttempt() {
        return kind == Kind.ROW_FAILURE || kind == Kind.DATA_ERROR;
    }

    public static ReplayOutcome success(MergeAction action) {
        return new ReplayOutcome(Kind.SUCCESS, action, null, null, null);
    }

    public static ReplayOutcome rowFailure(String code, String message) {
        return new ReplayOutcome(Kind.ROW_FAILURE, null, code, message, null);
    }

    public static ReplayOutcome dataError(String code, String message) {
        return new ReplayOutcome(Kind.DATA_ERROR, null, code, message, null);
    }

    public static ReplayOutcome configMissing(String message) {
        return new ReplayOutcome(Kind.CONFIG_MISSING, null, "CONFIG_MISSING", message, null);
    }

    public static ReplayOutcome pgFailure(Throwable cause) {
        return new ReplayOutcome(Kind.PG_FAILURE, null, "PG_CONNECTION_ERROR",
                cause == null ? "PG 级故障" : cause.getMessage(), cause);
    }
}
