package com.lawrence.supportagent.sharedkernel.api;

import com.lawrence.supportagent.observability.TraceIdFilter;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;

/** 把可预期异常映射为安全、稳定的统一 JSON 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private final TimeProvider timeProvider;

    /**
     * 使用统一时间端口创建异常处理器。
     *
     * @param timeProvider 当前时间端口
     */
    public GlobalExceptionHandler(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    /** 将请求校验错误映射为 400。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResult<Void>> handleValidation(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "COMMON_VALIDATION_FAILED", "请求参数不符合要求", request);
    }

    /** 将应用异常按稳定错误码映射为对应 HTTP 状态。 */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResult<Void>> handleApplication(ApplicationException exception,
                                                              HttpServletRequest request) {
        return response(statusOf(exception), exception.errorCode().name(), exception.getMessage(), request);
    }

    /** 将未预期异常隐藏为不泄露内部信息的 500 响应。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_INTERNAL_ERROR", "服务端发生未预期错误", request);
    }

    /** 构造包含当前 traceId 的错误响应。 */
    private ResponseEntity<ApiResult<Void>> response(HttpStatus status, String code, String message,
                                                     HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String traceId = value == null ? "unavailable" : value.toString();
        return ResponseEntity.status(status)
                .body(ApiResult.failure(code, message, traceId, timeProvider.now()));
    }

    /** 根据公开错误语义选择 HTTP 状态，避免所有应用异常被误报为冲突。 */
    private HttpStatus statusOf(ApplicationException exception) {
        return switch (exception.errorCode()) {
            case COMMON_VALIDATION_FAILED, KNOWLEDGE_SENSITIVE_CONTENT,
                    MEMORY_SENSITIVE_CONTENT -> HttpStatus.BAD_REQUEST;
            case CHAT_PROMPT_INJECTION_BLOCKED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AUTH_INVALID_CREDENTIALS, AUTH_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AUTH_FORBIDDEN, AUTH_SELF_DISABLE_FORBIDDEN,
                    AUTH_SELF_ROLE_CHANGE_FORBIDDEN, AUTH_PASSWORD_CHANGE_REQUIRED -> HttpStatus.FORBIDDEN;
            case AUTH_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TICKET_NOT_FOUND, KNOWLEDGE_NOT_FOUND, ASYNC_TASK_NOT_FOUND, AUTH_USER_NOT_FOUND,
                    TICKET_SUGGESTION_NOT_FOUND, CHAT_CONVERSATION_EXPIRED,
                    MEMORY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case COMMON_CONFLICT, COMMON_IDEMPOTENCY_IN_PROGRESS,
                    COMMON_IDEMPOTENCY_KEY_REUSED, TICKET_STATUS_CONFLICT,
                    TICKET_VERSION_CONFLICT, KNOWLEDGE_DUPLICATE_CONTENT,
                    KNOWLEDGE_STATUS_CONFLICT, KNOWLEDGE_VERSION_CONFLICT,
                    ASYNC_TASK_NOT_RETRYABLE, CHAT_VERSION_CONFLICT,
                    CHAT_CONVERSATION_BUSY, CHAT_MESSAGE_ID_REUSED,
                    TICKET_SUGGESTION_IN_PROGRESS, AUTH_USERNAME_CONFLICT,
                    AUTH_USER_VERSION_CONFLICT, AUTH_PASSWORD_REUSED, MEMORY_VERSION_CONFLICT,
                    MEMORY_STATUS_CONFLICT, MEMORY_DUPLICATE_CONTENT -> HttpStatus.CONFLICT;
            case DEPENDENCY_UNAVAILABLE, DASHSCOPE_NOT_CONFIGURED,
                    KNOWLEDGE_INDEX_FAILED, RETRIEVAL_FAILED,
                    CHAT_MODEL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CHAT_ANSWER_VALIDATION_FAILED -> HttpStatus.BAD_GATEWAY;
            case COMMON_INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
