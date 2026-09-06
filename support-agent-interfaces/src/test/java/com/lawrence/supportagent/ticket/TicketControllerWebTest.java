package com.lawrence.supportagent.ticket;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.GlobalExceptionHandler;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证工单 HTTP 参数缺失和枚举错误均使用统一 400 响应。 */
class TicketControllerWebTest {
    private MockMvc mockMvc;

    /** 使用真实校验和异常处理器装配独立 MVC 测试。 */
    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = () -> Instant.parse("2026-09-04T01:00:00Z");
        TicketController controller = new TicketController(mock(TicketCommandUseCase.class),
                mock(TicketQueryUseCase.class), new ApiResponseFactory(timeProvider));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider)).build();
    }

    /** 验证提交请求缺少必填 version 时不会被默认为零。 */
    @Test
    void shouldRejectMissingVersion() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/T000000000001/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"submit-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 验证未知状态枚举由统一异常处理器映射为校验失败。 */
    @Test
    void shouldRejectUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/tickets").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }
}
