package com.lawrence.supportagent.chat;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证会话重置和删除的预期版本参数使用统一校验错误响应。 */
class ConversationControllerWebTest {
    private static final String CONVERSATION = "10000000-0000-0000-0000-000000000001";
    private MockMvc mockMvc;

    /** 使用真实 Bean Validation 和统一异常处理器建立独立 MVC 测试。 */
    @BeforeEach
    void setUp() {
        TimeProvider time = () -> Instant.parse("2026-09-20T08:00:00Z");
        ConversationController controller = new ConversationController(
                mock(ConversationLifecycleUseCase.class), new ApiResponseFactory(time));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(time)).build();
    }

    /** 重置请求缺少 expectedVersion 时必须返回统一 400。 */
    @Test
    void shouldRejectResetWithoutExpectedVersion() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/reset")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 删除请求缺少 expectedVersion 查询参数时必须返回统一 400。 */
    @Test
    void shouldRejectDeleteWithoutExpectedVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/conversations/" + CONVERSATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }
}
