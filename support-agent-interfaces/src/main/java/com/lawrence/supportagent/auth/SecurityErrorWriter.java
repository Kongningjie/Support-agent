package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.observability.TraceIdFilter;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** 将 Spring Security 的 401/403 转换为统一且不泄露内部信息的 JSON。 */
public class SecurityErrorWriter {
    private final ObjectMapper mapper;
    private final TimeProvider time;

    /** 注入统一 JSON 和时间端口。 */
    public SecurityErrorWriter(ObjectMapper mapper, TimeProvider time) {
        this.mapper = mapper;
        this.time = time;
    }

    /** 写入统一认证或授权失败响应。 */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String code, String message) throws IOException {
        Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResult.failure(code, message,
                trace == null ? "unavailable" : trace.toString(), time.now()));
    }
}
