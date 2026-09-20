package com.lawrence.supportagent.memory;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** 验证长期记忆写接口缺少幂等键或版本时返回统一校验错误。 */
class UserMemoryControllerWebTest {
    private static final String MEMORY = "10000000-0000-0000-0000-000000000001";
    private MockMvc mockMvc;

    /** 使用真实 Bean Validation 和统一异常处理器建立独立 MVC 测试。 */
    @BeforeEach void setUp() {
        TimeProvider time = () -> Instant.parse("2026-09-20T08:00:00Z");
        UserMemoryController controller = new UserMemoryController(
                mock(UserMemoryUseCase.class), new ApiResponseFactory(time));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(time)).build();
    }

    /** 设置修改缺少 Idempotency-Key 时必须返回统一 400。 */
    @Test void shouldRejectSettingsUpdateWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/memory-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 永久删除缺少 expectedVersion 时必须返回统一 400。 */
    @Test void shouldRejectDeleteWithoutExpectedVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/memories/" + MEMORY)
                        .header("Idempotency-Key", "delete-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }
}
