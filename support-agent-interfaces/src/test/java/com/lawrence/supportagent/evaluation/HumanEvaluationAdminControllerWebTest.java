package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.GlobalExceptionHandler;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.DatasetState;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Review;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.ReviewStatus;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Score;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证人工评测公共请求契约拒绝缺项和越界分数。 */
class HumanEvaluationAdminControllerWebTest {
    private static final String SAMPLE = "10000000-0000-0000-0000-000000000001";
    private MockMvc mockMvc;

    /** 装配独立 MVC 控制器、参数校验与统一错误映射。 */
    @BeforeEach void setUp() {
        TimeProvider time = () -> Instant.parse("2026-10-12T00:00:00Z");
        mockMvc = MockMvcBuilders.standaloneSetup(new HumanEvaluationAdminController(
                        mock(HumanEvaluationUseCase.class), new ApiResponseFactory(time)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(time)).build();
    }

    /** 五维任一缺失时不能默认当成零分。 */
    @Test void shouldRejectMissingDimension() throws Exception {
        mockMvc.perform(post("/api/v1/admin/human-evaluations/" + SAMPLE + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factual\":2,\"citation\":2,\"completeness\":2,"
                                + "\"actionability\":2,\"reason\":\"有理由\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 超出 0～2 的评分必须在协议层拒绝。 */
    @Test void shouldRejectOutOfRangeScore() throws Exception {
        mockMvc.perform(post("/api/v1/admin/human-evaluations/" + SAMPLE + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factual\":3,\"citation\":2,\"completeness\":2,"
                                + "\"actionability\":2,\"clarity\":2,\"reason\":\"有理由\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 来源标识不能为空，避免无效样本占用来源唯一约束。 */
    @Test void shouldRejectMissingSource() throws Exception {
        mockMvc.perform(post("/api/v1/admin/human-evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 已决响应保留五维字段名与评分，供前端按字段含义展示。 */
    @Test void shouldMapDecidedScoreToDocumentedResponse() {
        UUID sampleId = UUID.fromString(SAMPLE);
        UUID reviewerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        Score score = new Score(2, 1, 2, 1, 2);
        Sample sample = new Sample(sampleId, "FIXED", "A8-GROUND-001", UUID.randomUUID(),
                "问题", "回答", "证据", reviewerId, ReviewStatus.DECIDED, DatasetState.NONE, 0, now);
        var response = HumanEvaluationAdminController.DetailResponse.from(new HumanEvaluationUseCase.Detail(
                sample, List.of(new Review(reviewerId, "FIRST", score, "理由", now)), score));
        assertThat(response.finalScore()).isEqualTo(new HumanEvaluationAdminController.ScoreResponse(2, 1, 2, 1, 2));
        assertThat(response.reviews().getFirst().score()).isEqualTo(response.finalScore());
    }
}
