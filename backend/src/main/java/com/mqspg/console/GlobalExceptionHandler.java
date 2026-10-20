package com.mqspg.console;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ErrorSeverity;
import com.mqspg.common.error.ProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * 统一异常到 {@link ApiResponse} 的映射。
 *
 * <p><b>三条规矩</b>（里程碑 H4）：
 * <ol>
 *   <li><b>不把内部细节回吐给前端</b>。原来兜底分支直接把 {@code e.getMessage()} 塞进响应，
 *       可能含 SQL、连接串、类名；约束冲突分支连约束名与 Key 值一起返回。
 *       现在对外只给一句中文 + {@code traceId}，完整信息进日志。</li>
 *   <li><b>状态码按错误码的语义映射</b>，不再一律 400。此前所有
 *       {@code ProcessingException} 都是 400，而 {@code CONFIG_MISSING} 的严重度是 FATAL ——
 *       一旦按严重度映射，几十处表单校验会变成 503。所以先把
 *       {@code NOT_FOUND} / {@code INVALID_INPUT} 从 {@code CONFIG_MISSING} 里拆出来。</li>
 *   <li><b>补齐 400 分支</b>：非法 JSON、路径参数类型错误过去都落进兜底 → 500 + Jackson 原文。</li>
 * </ol>
 *
 * <p>{@code traceId} 是本次新增的：用户看到「操作失败（traceId=…）」，
 * 运维拿这个 id 在生产日志里 grep 就能拿到完整堆栈。没有它，「不泄露细节」
 * 与「用户能报障」是矛盾的。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProcessingException.class)
    public ResponseEntity<ApiResponse<Void>> handleProcessing(ProcessingException e) {
        String traceId = newTraceId();
        HttpStatus status = statusOf(e.getCode());

        // 服务端故障记 error（要能按 traceId 捞到堆栈）；其余记 warn（用户操作不当，不是故障）
        if (status.is5xxServerError()) {
            log.error("业务异常(服务端) traceId={} code={} stage={} message={}",
                    traceId, e.getCode(), e.getStage(), e.getMessage(), e);
        } else {
            log.warn("业务异常 traceId={} code={} stage={} message={}",
                    traceId, e.getCode(), e.getStage(), e.getMessage());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(e.getCode().name(), e.getMessage(), traceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        String traceId = newTraceId();
        log.warn("非法参数 traceId={} message={}", traceId, e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("INVALID_INPUT", safeMessage(e.getMessage()), traceId));
    }

    /** 请求体不是合法 JSON / 类型对不上。用户能自己改，因此 400 + 固定中文。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        String traceId = newTraceId();
        log.warn("请求体无法解析 traceId={} 原因={}", traceId, e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("INVALID_INPUT", "请求体不是合法 JSON", traceId));
    }

    /**
     * Bean Validation 失败（DTO 上的 {@code @NotBlank} / {@code @Size} 等）。
     *
     * <p>为什么要把字段级信息带出去：这类错误的全部价值就在「哪个字段、为什么」。
     * 只说「参数不合法」等于让用户回去自己试。这里把 {@code field: message}
     * 拼进 {@code message}（前端表单下方直接展示），同时原样保留在日志里。
     *
     * <p>取 {@code getDefaultMessage()} 而不是 Jackson/Spring 的原始描述 ——
     * 注解上的 message 已经是中文，直接可用。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String traceId = newTraceId();
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> "%s: %s".formatted(f.getField(), f.getDefaultMessage()))
                .distinct()
                .collect(java.util.stream.Collectors.joining("；"));
        log.warn("请求参数校验未通过 traceId={} 明细={}", traceId, detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("INVALID_INPUT", detail.isEmpty() ? "请求参数不合法" : detail, traceId));
    }

    /** 路径/查询参数类型不对，例如 {@code /api/routes/abc}。同样是调用方可修正的错误。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String traceId = newTraceId();
        log.warn("参数类型错误 traceId={} 参数={} 值={} 期望类型={}", traceId,
                e.getName(), e.getValue(),
                e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_INPUT",
                "参数「%s」的类型不正确".formatted(e.getName()), traceId));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String traceId = newTraceId();
        log.warn("缺少必需参数 traceId={} 参数={}", traceId, e.getParameterName());
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_INPUT",
                "缺少必需参数「%s」".formatted(e.getParameterName()), traceId));
    }

    /**
     * 数据库约束冲突。
     *
     * <p>写接口都对唯一约束做了预检并给出可读信息，走到这里说明是并发下的竞态。
     * 兜底成 400 而不是 500：这仍然是**调用方输入**的问题，不是服务端缺陷。
     * 但**不再返回约束名与 Key 值** —— 那里面有表名、列名和用户数据，
     * 属于内部结构信息；完整内容只进日志。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String traceId = newTraceId();
        log.warn("数据库约束冲突 traceId={} 原因={}", traceId, e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail("CONSTRAINT_VIOLATION",
                "数据冲突，请刷新后重试", traceId));
    }

    /**
     * 路径不存在。
     *
     * <p>Spring 6.1 起，未匹配到任何 handler 的请求不再抛 NoHandlerFoundException，
     * 而是抛 NoResourceFoundException（静态资源查找失败）。它落到下面的
     * {@code Exception} 兜底分支就会变成 **500 + INTERNAL_ERROR + error 级堆栈** ——
     * 访问一个拼错的 URL 就能在日志里刷一条「服务内部错误」，既误导排查方向，
     * 也让人以为后端坏了。这里显式映射成 404。
     *
     * <p>顺带的好处：springdoc 关闭后访问 /v3/api-docs 也会走到这条干净路径，
     * 而不是返回 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        String traceId = newTraceId();
        // DEBUG 而非 WARN：这是调用方拼错路径，不是服务端问题，没必要刷警告
        log.debug("路径不存在 traceId={} path={}", traceId, e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("NOT_FOUND", "接口不存在：%s".formatted(e.getResourcePath()), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String traceId = newTraceId();
        // 这里是唯一保留 error 级堆栈的兜底分支 —— 前端只拿到 traceId
        log.error("未处理异常 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("INTERNAL_ERROR",
                        "服务内部错误，请把 traceId 提供给运维排查", traceId));
    }

    /**
     * 错误码 → HTTP 状态码。
     *
     * <p>映射依据是**调用方能否自行修正**，而不是严重度本身：
     * <ul>
     *   <li>404：目标对象不存在，改请求即可；</li>
     *   <li>400：入参不合法 / 当前状态不允许该操作 / 需要二次确认的冲突；</li>
     *   <li>503：服务端依赖真的不可用（PG 连不上、超时），重试可能有用；</li>
     *   <li>其余 FATAL 按 503，其余按 400 —— 保守起见，未知码不让服务端背 5xx。</li>
     * </ul>
     */
    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, CONFIG_GONE -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT, CONFLICT, JSON_PARSE_ERROR, JSLT_ERROR, PATH_ERROR,
                 TYPE_CONVERSION_ERROR, ENUM_MAPPING_ERROR, DATETIME_FORMAT_ERROR,
                 EXPRESSION_ERROR, MISSING_REQUIRED_FIELD, PG_CONSTRAINT_VIOLATION,
                 PG_TYPE_ERROR, PG_UNKNOWN -> HttpStatus.BAD_REQUEST;
            case PG_CONNECTION_ERROR, PG_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            // CONFIG_MISSING 保留 FATAL 语义：走到接口层说明配置库/路由配置读不到，
            // 是服务端依赖问题，不是用户填错了表单项
            case CONFIG_MISSING -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> code.getSeverity() == ErrorSeverity.FATAL
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 兜底：正常路径的中文提示原样返回，空消息给一句通用文案。 */
    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "请求不合法" : message;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
