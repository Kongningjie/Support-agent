package com.lawrence.supportagent.observability;

import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为每个 HTTP 请求生成服务端可信的追踪标识。 */
public final class TraceIdFilter extends OncePerRequestFilter {
    /** 请求范围内保存服务端 traceId 的属性名。 */
    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private final UuidGenerator uuidGenerator;

    /**
     * 使用统一 UUID 端口创建过滤器，便于测试并避免散落的标识生成策略。
     *
     * @param uuidGenerator UUID 生成端口
     */
    public TraceIdFilter(UuidGenerator uuidGenerator) {
        this.uuidGenerator = uuidGenerator;
    }

    /** 生成 traceId，写入 MDC、请求属性和响应头，并在请求结束时清理。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = uuidGenerator.generate().toString();
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}
