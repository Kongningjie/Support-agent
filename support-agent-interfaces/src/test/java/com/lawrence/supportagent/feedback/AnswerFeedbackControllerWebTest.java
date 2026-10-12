package com.lawrence.supportagent.feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.GlobalExceptionHandler;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证反馈请求校验和安全错误的 HTTP 语义。 */
class AnswerFeedbackControllerWebTest {
    private static final String RUN = "10000000-0000-0000-0000-000000000001";
    private MockMvc mockMvc;
    private AnswerFeedbackUseCase useCase;

    /** 装配独立 MVC 控制器、Bean Validation 与统一异常处理。 */
    @BeforeEach void setUp() {
        TimeProvider time = () -> Instant.parse("2026-10-12T00:00:00Z");
        useCase = mock(AnswerFeedbackUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AnswerFeedbackController(useCase, new ApiResponseFactory(time)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(time)).build();
    }

    /** 未提供评分时不能写入反馈。 */
    @Test void shouldRejectMissingRating() throws Exception {
        mockMvc.perform(put("/api/v1/answer-feedback/" + RUN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 删除缺少当前版本时返回 400 而不是误删。 */
    @Test void shouldRejectDeleteWithoutVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/answer-feedback/" + RUN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 非本人或非成功运行以统一 404 隐藏存在性。 */
    @Test void shouldMapHiddenRunToNotFound() throws Exception {
        when(useCase.put(any(), any(), any(), any(), any(), any())).thenThrow(
                new ApplicationException(ErrorCode.FEEDBACK_NOT_FOUND, "反馈或可评价回答不存在"));
        mockMvc.perform(put("/api/v1/answer-feedback/" + RUN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"HELPFUL\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEEDBACK_NOT_FOUND"));
    }
}
