package com.lawrence.supportagent.sharedkernel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.observability.TraceIdFilter;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 验证统一响应、分页约束、异常映射和 traceId 请求基础设施。 */
class FoundationWebTest {
    private static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");
    private static final UUID TRACE_ID = UUID.fromString("853ddf68-2f67-48f8-afeb-908f0e62c680");

    /** 验证成功响应与安全失败响应的稳定字段语义。 */
    @Test
    void shouldCreateStableApiResults() {
        ApiResult<String> success = ApiResult.success("payload", TRACE_ID.toString(), NOW);
        ApiResult<Void> failure = ApiResult.failure("COMMON_CONFLICT", "状态冲突",
                TRACE_ID.toString(), NOW);

        assertEquals("SUCCESS", success.code());
        assertEquals("payload", success.data());
        assertEquals("COMMON_CONFLICT", failure.code());
        assertNull(failure.data());
    }

    /** 验证分页集合不可变且页码和页大小越界会被拒绝。 */
    @Test
    void shouldValidatePageResult() {
        PageResult<String> page = new PageResult<>(List.of("one"), 1, 20, 1, 1);

        assertEquals(List.of("one"), page.items());
        assertThrows(IllegalArgumentException.class,
                () -> new PageResult<>(List.of(), 0, 20, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PageResult<>(List.of(), 1, 101, 0, 0));
    }

    /** 验证应用错误码会映射为对应 HTTP 状态并携带统一时间和 traceId。 */
    @Test
    void shouldMapApplicationErrorBySemantics() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(() -> NOW);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, TRACE_ID.toString());

        var response = handler.handleApplication(
                new ApplicationException(ErrorCode.DEPENDENCY_UNAVAILABLE, "依赖不可用"), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(TRACE_ID.toString(), response.getBody().traceId());
        assertEquals(NOW, response.getBody().timestamp());
    }

    /** 验证过滤器写入可信 traceId，并在请求结束后清理 MDC。 */
    @Test
    void shouldCreateAndCleanTraceId() throws Exception {
        TraceIdFilter filter = new TraceIdFilter(() -> TRACE_ID);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(TRACE_ID.toString(), response.getHeader("X-Trace-Id"));
        assertEquals(TRACE_ID.toString(), request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        assertTrue(MDC.getCopyOfContextMap() == null
                || !MDC.getCopyOfContextMap().containsKey("traceId"));
    }
}
