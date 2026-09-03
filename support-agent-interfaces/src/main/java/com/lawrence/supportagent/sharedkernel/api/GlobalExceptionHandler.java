package com.lawrence.supportagent.sharedkernel.api;

import com.lawrence.supportagent.observability.TraceIdFilter;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
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
            case COMMON_VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case COMMON_CONFLICT -> HttpStatus.CONFLICT;
            case DEPENDENCY_UNAVAILABLE, DASHSCOPE_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case COMMON_INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
