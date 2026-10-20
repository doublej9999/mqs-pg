package com.mqspg.common.error;

import lombok.Getter;

/**
 * 错误码及其默认级别（tech-design.md §8.6）。
 *
 * <p>注：{@link ErrorSeverity#DATA} 类错误默认直接进 DLQ（不重试）。PRD §23 字面要求
 * 「数据处理错误重试 10 次」，该行为可通过路由级开关 {@code retryDataErrors} 恢复，
 * 见 tech-design.md 附录 C-01。
 */
@Getter
public enum ErrorCode {

    // ---- 数据/转换类 ----
    //
    // 取值与转换阶段的失败完全由**消息内容**决定：同一条消息重试 10 次结果不变。
    // 因此一律标为 DATA（直接进 DLQ），避免把算力浪费在必然失败的重试上。
    // 若某路由的上游是最终一致的（先发事件、后补字段），可用路由级开关
    // retryDataErrors 打开重试，见附录 C-01。
    JSON_PARSE_ERROR(ErrorStage.PARSE, ErrorSeverity.DATA),
    JSLT_ERROR(ErrorStage.JSLT, ErrorSeverity.DATA),
    PATH_ERROR(ErrorStage.PATH, ErrorSeverity.DATA),
    TYPE_CONVERSION_ERROR(ErrorStage.CONVERT, ErrorSeverity.DATA),
    ENUM_MAPPING_ERROR(ErrorStage.CONVERT, ErrorSeverity.DATA),
    DATETIME_FORMAT_ERROR(ErrorStage.CONVERT, ErrorSeverity.DATA),
    EXPRESSION_ERROR(ErrorStage.EXPRESSION, ErrorSeverity.DATA),
    MISSING_REQUIRED_FIELD(ErrorStage.VALIDATE, ErrorSeverity.DATA),

    // ---- 写入类 ----
    PG_CONSTRAINT_VIOLATION(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),
    PG_TYPE_ERROR(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),
    PG_CONNECTION_ERROR(ErrorStage.WRITE, ErrorSeverity.FATAL),
    PG_TIMEOUT(ErrorStage.WRITE, ErrorSeverity.FATAL),
    PG_UNKNOWN(ErrorStage.WRITE, ErrorSeverity.TRANSIENT),

    // ---- 系统类 ----
    CONFIG_MISSING(ErrorStage.RECEIVE, ErrorSeverity.FATAL),
    /**
     * 重试任务绑定的配置版本**永久**不可用（版本被删、路由被删、目标表被删）。
     *
     * <p>与 {@link #CONFIG_MISSING} 的区别是「临时」还是「永久」：配置读取时的
     * 数据库抖动、路由暂时没有 ACTIVE 版本，都属于**临时**问题，等下一轮就好；
     * 而绑定的那个版本号已经不在库里了，重试一万次也不会回来。
     *
     * <p>严重度取 {@code DATA} 而不是 {@code FATAL}：它不回退到 MQ 重投、
     * 也不暂停消费，只决定这条重试任务能撑几轮缓冲后进 DLQ。
     */
    CONFIG_GONE(ErrorStage.RECEIVE, ErrorSeverity.DATA),
    /**
     * 当前状态下不允许该操作（如「路由还有未完成的重试任务」）。
     *
     * <p>与 {@link #CONFIG_MISSING} 的区别是**语义**而不是严重度：调用方需要据此
     * 走不同的交互分支（弹「仍然删除」的二次确认），因此必须是一个独立错误码，
     * 不能靠中文文案区分 —— 文案一改，前端的分支就静默失效。
     */
    CONFLICT(ErrorStage.RECEIVE, ErrorSeverity.DATA),
    /**
     * 请求指向的对象不存在（路由 / 目标表 / 数据源 / 配置版本）。
     *
     * <p><b>为什么必须从 {@link #CONFIG_MISSING} 里拆出来</b>：那个码是
     * 「消费时读不到配置」的运行时故障，严重度 FATAL；但它长期被当成万能入参错误
     * 用了四十多处（「路由不存在」「名称已存在」「不可编辑」……）。
     * 一旦按严重度映射 HTTP 状态码，所有这些表单校验都会变成 503。
     * 拆出来之后：{@code NOT_FOUND} → 404，{@code INVALID_INPUT} → 400，
     * 真正 FATAL 的 {@link #PG_CONNECTION_ERROR} / {@link #PG_TIMEOUT} → 503。
     */
    NOT_FOUND(ErrorStage.RECEIVE, ErrorSeverity.DATA),
    /**
     * 入参不合法（必填缺失、格式不对、状态不允许该操作、重名冲突）。
     *
     * <p>与 {@link #NOT_FOUND} 分开是因为 HTTP 语义不同：同一个「路由」，
     * 不存在是 404，而「状态不是 DRAFT 所以不可编辑」是 400 —— 资源在，操作不允许。
     * 与 {@link #CONFLICT} 也分开：那个专指「需要前端走二次确认」的并发/占用冲突，
     * 前端靠它弹「仍然删除」。
     */
    INVALID_INPUT(ErrorStage.RECEIVE, ErrorSeverity.DATA),
    INTERNAL_ERROR(ErrorStage.RECEIVE, ErrorSeverity.TRANSIENT);

    private final ErrorStage stage;
    private final ErrorSeverity severity;

    ErrorCode(ErrorStage stage, ErrorSeverity severity) {
        this.stage = stage;
        this.severity = severity;
    }
}
