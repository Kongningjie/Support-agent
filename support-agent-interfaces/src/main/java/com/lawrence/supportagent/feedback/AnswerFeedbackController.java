package com.lawrence.supportagent.feedback;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将本人单次回答反馈的 JSON 契约适配到应用用例。 */
@Validated
@RestController
@RequestMapping("/api/v1/answer-feedback")
public class AnswerFeedbackController {
    private final AnswerFeedbackUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入反馈用例和统一响应工厂。 */
    public AnswerFeedbackController(AnswerFeedbackUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 查询本人对已成功回答的反馈；未评价返回 404。 */
    @Operation(summary = "查询本人回答反馈")
    @GetMapping("/{runId}")
    public ApiResult<FeedbackResponse> get(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable UUID runId, HttpServletRequest request) {
        return responses.success(FeedbackResponse.from(useCase.get(actor, runId)), request);
    }

    /** 首次提交或按版本更正本人反馈。 */
    @Operation(summary = "提交或更正本人回答反馈")
    @PutMapping("/{runId}")
    public ApiResult<FeedbackResponse> put(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable UUID runId,
                                            @Valid @RequestBody FeedbackRequest body,
                                            HttpServletRequest request) {
        return responses.success(FeedbackResponse.from(useCase.put(actor, runId,
                body.rating(), body.reason(), body.note(), body.expectedVersion())), request);
    }

    /** 按版本删除本人反馈内容，并保留不可见版本标记防止旧请求覆盖。 */
    @Operation(summary = "删除本人回答反馈")
    @DeleteMapping("/{runId}")
    public ApiResult<Void> delete(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @PathVariable UUID runId,
                                   @RequestParam @PositiveOrZero long expectedVersion,
                                   HttpServletRequest request) {
        useCase.delete(actor, runId, expectedVersion);
        return responses.success(null, request);
    }

    /**
     * @param rating HELPFUL 有帮助或 NOT_HELPFUL 无帮助
     * @param reason 无帮助时必填的原因；有帮助时必须为空
     * @param note 可选说明，最多 300 个 Unicode 字符且不得包含敏感信息
     * @param expectedVersion 首次创建为空；更正时为当前版本
     */
    public record FeedbackRequest(
            @NotNull @Schema(description = "帮助程度：HELPFUL 有帮助、NOT_HELPFUL 无帮助",
                    example = "NOT_HELPFUL", requiredMode = Schema.RequiredMode.REQUIRED)
            FeedbackRating rating,
            @Schema(description = "无帮助原因；有帮助时必须为空", nullable = true, example = "INCOMPLETE")
            FeedbackReason reason,
            @Size(max = 1200) @Schema(description = "可选说明，规范化后最多 300 个 Unicode 字符；禁止敏感信息",
                    nullable = true, example = "缺少恢复步骤") String note,
            @PositiveOrZero @Schema(description = "当前反馈版本；首次创建为空，更正时必填",
                    nullable = true, example = "0") Long expectedVersion) { }

    /**
     * @param runId 被评价的原始成功运行 UUID
     * @param rating 当前帮助程度
     * @param reason 无帮助原因，可为空
     * @param note 用户可选说明，可为空
     * @param version 乐观锁修订版本
     * @param createdAt 首次提交 UTC 时间
     * @param updatedAt 最近更正 UTC 时间
     */
    public record FeedbackResponse(
            @Schema(description = "被评价的原始成功运行 UUID") UUID runId,
            @Schema(description = "帮助程度：HELPFUL 或 NOT_HELPFUL") FeedbackRating rating,
            @Schema(description = "无帮助原因；有帮助时为空", nullable = true) FeedbackReason reason,
            @Schema(description = "可选说明；不会出现在管理员聚合中", nullable = true) String note,
            @Schema(description = "反馈修订版本，从 0 开始") long version,
            @Schema(description = "首次提交 UTC 时间") Instant createdAt,
            @Schema(description = "最近更正 UTC 时间") Instant updatedAt) {
        /** 将应用层反馈转换为不含用户 ID 的本人响应。 */
        public static FeedbackResponse from(AnswerFeedback value) {
            return new FeedbackResponse(value.runId(), value.rating(), value.reason(), value.note(),
                    value.version(), value.createdAt(), value.updatedAt());
        }
    }
}
