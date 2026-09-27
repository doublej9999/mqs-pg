package com.mqspg.common.model;

/**
 * PG 写入的逐行结果（PRD §11 / tech-design §9.2）。
 *
 * <p>{@link #SKIPPED_OLD_VERSION} 不由 PG 直接返回，而是由
 * 「输入 Key 集 − RETURNING 返回的 Key 集」推导得出。
 */
public enum MergeAction {

    /** 新记录插入。 */
    INSERTED,

    /** 新版本更新。 */
    UPDATED,

    /** 消息已处理，但数据比 PG 当前版本旧。 */
    SKIPPED_OLD_VERSION;

    /**
     * 将 PG {@code merge_action()} 的返回值映射为应用侧结果。
     *
     * @param pgAction PG 返回的 {@code 'INSERT'} / {@code 'UPDATE'} / {@code 'DELETE'}
     */
    public static MergeAction fromPgAction(String pgAction) {
        return switch (pgAction) {
            case "INSERT" -> INSERTED;
            case "UPDATE" -> UPDATED;
            default -> throw new IllegalArgumentException("非预期的 merge_action(): " + pgAction);
        };
    }

    /** 三种结果均需 ACK（PRD §27）。 */
    public boolean shouldAck() {
        return true;
    }
}
