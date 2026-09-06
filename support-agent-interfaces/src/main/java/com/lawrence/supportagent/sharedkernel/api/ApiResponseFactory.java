package com.lawrence.supportagent.sharedkernel.api;

import com.lawrence.supportagent.observability.TraceIdFilter;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 为 Controller 创建包含可信 traceId 和统一 UTC 时间的成功响应。 */
@Component
public class ApiResponseFactory {
    private final TimeProvider timeProvider;

    /** 注入统一时间端口。 */
    public ApiResponseFactory(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    /** 创建当前 HTTP 请求对应的统一成功响应。 */
    public <T> ApiResult<T> success(T data, HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ApiResult.success(data, traceId == null ? "unavailable" : traceId.toString(),
                timeProvider.now());
    }
}
