package com.lawrence.supportagent.feedback;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读反馈聚合，不暴露个体反馈或说明正文。 */
@RestController
@RequestMapping("/api/v1/admin/answer-feedback")
public class AdminAnswerFeedbackController {
    private final AnswerFeedbackUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入应用用例和统一响应工厂。 */
    public AdminAnswerFeedbackController(AnswerFeedbackUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 返回全量反馈的低敏分类计数，权限在安全过滤器和应用层双重校验。 */
    @Operation(summary = "查询管理员反馈聚合")
    @GetMapping("/summary")
    public ApiResult<SummaryResponse> summary(@AuthenticationPrincipal AuthenticatedUser actor,
                                               HttpServletRequest request) {
        FeedbackSummary value = useCase.summary(actor);
        return responses.success(new SummaryResponse(value.total(), value.byRating(), value.byReason()), request);
    }

    /**
     * @param total 当前反馈总条数
     * @param byRating 按帮助程度分类的计数，包含 0 值分类
     * @param byReason 按无帮助原因分类的计数，包含 0 值分类
     */
    public record SummaryResponse(
            @Schema(description = "当前反馈总条数", example = "12") long total,
            @Schema(description = "按 HELPFUL、NOT_HELPFUL 聚合的条数") Map<FeedbackRating, Long> byRating,
            @Schema(description = "按预定义无帮助原因聚合的条数") Map<FeedbackReason, Long> byReason) { }
}
