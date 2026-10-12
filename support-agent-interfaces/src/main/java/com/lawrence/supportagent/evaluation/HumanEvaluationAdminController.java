package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.DatasetState;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Review;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.ReviewStatus;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Score;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员人工评测契约；盲评分的可见范围由应用用例裁剪。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/human-evaluations")
public class HumanEvaluationAdminController {
    private final HumanEvaluationUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入评测用例和统一 JSON 响应工厂。 */
    public HumanEvaluationAdminController(HumanEvaluationUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 分页查询无帮助反馈候选，仅返回标识、原因与时间。 */
    @Operation(summary = "查询人工评测反馈候选")
    @GetMapping("/feedback-candidates")
    public ApiResult<List<FeedbackCandidateResponse>> candidates(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return responses.success(useCase.candidates(actor, page, size).stream()
                .map(FeedbackCandidateResponse::from).toList(), request);
    }

    /** 列出已有固定回答评测用例的标识和问题。 */
    @Operation(summary = "查询固定回答用例候选")
    @GetMapping("/fixed-candidates")
    public ApiResult<List<FixedCandidateResponse>> fixedCandidates(
            @AuthenticationPrincipal AuthenticatedUser actor, HttpServletRequest request) {
        return responses.success(useCase.fixedCandidates(actor).stream()
                .map(FixedCandidateResponse::from).toList(), request);
    }

    /** 分页查询已确认脱敏的人工评测样本。 */
    @Operation(summary = "查询人工评测样本")
    @GetMapping
    public ApiResult<List<SampleResponse>> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                                 @RequestParam(defaultValue = "1") @Min(1) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                 HttpServletRequest request) {
        return responses.success(useCase.list(actor, page, size).stream().map(SampleResponse::from).toList(), request);
    }

    /** 读取样本与当前管理员可见的评分，未决时隐藏其他管理员评分。 */
    @Operation(summary = "查询人工评测样本详情")
    @GetMapping("/{sampleId}")
    public ApiResult<DetailResponse> details(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable UUID sampleId, HttpServletRequest request) {
        return responses.success(DetailResponse.from(useCase.details(actor, sampleId)), request);
    }

    /** 人工确认脱敏后创建样本；不自动抓取或持久化原始会话。 */
    @Operation(summary = "创建脱敏人工评测样本")
    @PostMapping
    public ApiResult<SampleResponse> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @Valid @RequestBody CreateRequest body,
                                             HttpServletRequest request) {
        return responses.success(SampleResponse.from(useCase.create(actor, body.sourceType(), body.sourceRef(),
                body.spaceId(), body.question(), body.answer(), body.evidence(), body.sanitizedConfirmed())), request);
    }

    /** 独立提交不可更改的五维评分，服务端原子决定本次评审角色。 */
    @Operation(summary = "提交人工盲评或分歧复核")
    @PostMapping("/{sampleId}/reviews")
    public ApiResult<DetailResponse> review(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable UUID sampleId,
                                             @Valid @RequestBody ReviewRequest body,
                                             HttpServletRequest request) {
        Score score = new Score(body.factual(), body.citation(), body.completeness(),
                body.actionability(), body.clarity());
        return responses.success(DetailResponse.from(useCase.review(actor, sampleId, score, body.reason())), request);
    }

    /** 显式纳入开发集或在另一操作中审批晋升回归集。 */
    @Operation(summary = "晋升人工评测样本")
    @PostMapping("/{sampleId}/promotions")
    public ApiResult<DetailResponse> promote(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable UUID sampleId,
                                              @Valid @RequestBody PromotionRequest body,
                                              HttpServletRequest request) {
        return responses.success(DetailResponse.from(useCase.promote(actor, sampleId, body.target())), request);
    }

    /**
     * @param sourceType FIXED 固定用例或 FEEDBACK 无帮助反馈
     * @param sourceRef 固定用例 ID 或成功运行 UUID
     * @param spaceId 样本所属知识空间 UUID；固定用例须为 GLOBAL
     * @param question 人工确认脱敏的问题，最多 2000 字符
     * @param answer 人工确认脱敏的回答，最多 10000 字符
     * @param evidence 允许评审查看的脱敏证据，最多 10000 字符
     * @param sanitizedConfirmed 管理员是否已逐项确认脱敏
     */
    public record CreateRequest(
            @NotBlank @Schema(description = "来源类型 FIXED 或 FEEDBACK", example = "FIXED") String sourceType,
            @NotBlank @Schema(description = "固定回答用例 ID 或反馈运行 UUID", example = "A8-GROUND-001") String sourceRef,
            @NotNull @Schema(description = "所属知识空间 UUID；固定用例必须是 GLOBAL") UUID spaceId,
            @NotBlank @Size(max = 8000) @Schema(description = "脱敏问题，最多 2000 个 Unicode 字符") String question,
            @NotBlank @Size(max = 40000) @Schema(description = "脱敏回答，最多 10000 个 Unicode 字符") String answer,
            @NotBlank @Size(max = 40000) @Schema(description = "脱敏证据，最多 10000 个 Unicode 字符") String evidence,
            @Schema(description = "已人工确认所有副本脱敏后必须为 true", example = "true")
            boolean sanitizedConfirmed) { }

    /**
     * @param factual 事实正确，0 明显错误、1 部分满足、2 满足
     * @param citation 引用支持，0 无支持、1 部分支持、2 支持或不适用
     * @param completeness 完整性，0 不完整、1 部分完整、2 完整
     * @param actionability 可操作性，0 不可操作、1 部分可操作、2 可操作
     * @param clarity 表达清晰度，0 不清晰、1 部分清晰、2 清晰
     * @param reason 覆盖五维的评分理由；无引用义务时注明引用不适用
     */
    public record ReviewRequest(
            @NotNull @Min(0) @Max(2) @Schema(description = "事实正确 0～2 分，必填") Integer factual,
            @NotNull @Min(0) @Max(2) @Schema(description = "引用支持 0～2 分，必填") Integer citation,
            @NotNull @Min(0) @Max(2) @Schema(description = "完整性 0～2 分，必填") Integer completeness,
            @NotNull @Min(0) @Max(2) @Schema(description = "可操作性 0～2 分，必填") Integer actionability,
            @NotNull @Min(0) @Max(2) @Schema(description = "表达清晰度 0～2 分，必填") Integer clarity,
            @NotBlank @Size(max = 1000) @Schema(description = "五维评分理由，禁止敏感信息") String reason) { }

    /** @param target DEVELOPMENT 开发集或 REGRESSION 回归集。 */
    public record PromotionRequest(@NotNull @Schema(description = "目标层级 DEVELOPMENT 或 REGRESSION")
                                   DatasetState target) { }

    /** 无帮助反馈候选的最小公开信息，不包含用户、说明和回答正文。 */
    public record FeedbackCandidateResponse(
            @Schema(description = "被评回答的成功运行 UUID") UUID runId,
            @Schema(description = "所属会话 UUID；仅用于管理员显式查看仍可用的原会话") UUID conversationId,
            @Schema(description = "用户选择的预定义无帮助原因") String reason,
            @Schema(description = "反馈首次提交 UTC 时间") Instant createdAt) {
        /** 裁剪内部候选为公开元数据。 */
        public static FeedbackCandidateResponse from(HumanEvaluationRepository.FeedbackCandidate value) {
            return new FeedbackCandidateResponse(value.runId(), value.conversationId(),
                    value.reason(), value.createdAt());
        }
    }

    /** 固定回答用例候选；不包含实际待评回答。 */
    public record FixedCandidateResponse(
            @Schema(description = "冻结回答用例 ID") String caseId,
            @Schema(description = "固定用例类别") String category,
            @Schema(description = "固定用例的用户问题") String question) {
        /** 转换冻结用例的最小选择信息。 */
        public static FixedCandidateResponse from(HumanEvaluationUseCase.FixedCandidate value) {
            return new FixedCandidateResponse(value.caseId(), value.category(), value.question());
        }
    }

    /** 仅包含脱敏副本、评审状态和独立数据集版本的管理员响应。 */
    public record SampleResponse(
            @Schema(description = "人工评测样本公开 UUID") UUID id,
            @Schema(description = "样本来源 FIXED 固定用例或 FEEDBACK 无帮助反馈") String sourceType,
            @Schema(description = "固定用例 ID 或原始运行 UUID") String sourceRef,
            @Schema(description = "样本所属知识空间 UUID") UUID spaceId,
            @Schema(description = "管理员确认脱敏后的问题副本") String question,
            @Schema(description = "管理员确认脱敏后的回答副本") String answer,
            @Schema(description = "允许评审查看的脱敏证据副本") String evidence,
            @Schema(description = "当前评审状态") ReviewStatus status,
            @Schema(description = "当前独立人工评测集层级") DatasetState datasetState,
            @Schema(description = "最近一次数据集晋升的全局版本；未纳入时为 0") long datasetVersion,
            @Schema(description = "样本创建 UTC 时间") Instant createdAt) {
        /** 去掉创建人的用户标识，降低列表披露。 */
        public static SampleResponse from(Sample sample) {
            return new SampleResponse(sample.id(), sample.sourceType(), sample.sourceRef(), sample.spaceId(),
                    sample.question(), sample.answer(), sample.evidence(), sample.status(),
                    sample.datasetState(), sample.datasetVersion(), sample.createdAt());
        }
    }

    /** 仅在结论形成后或本人是提交者时包含评分。 */
    public record DetailResponse(
            @Schema(description = "脱敏样本及当前状态") SampleResponse sample,
            @Schema(description = "未决时仅包含本人评分；结论形成后包含全部评分") List<ReviewResponse> reviews,
            @Schema(description = "结论形成后的最终五维评分；未决时为空", nullable = true)
            ScoreResponse finalScore) {
        /** 从应用用例已裁剪的结果构造 HTTP 响应。 */
        public static DetailResponse from(HumanEvaluationUseCase.Detail value) {
            return new DetailResponse(SampleResponse.from(value.sample()), value.reviews().stream()
                    .map(ReviewResponse::from).toList(), ScoreResponse.from(value.finalScore()));
        }
    }

    /** 对外逐字段解释五维评分，避免客户端误解分值含义。 */
    public record ScoreResponse(
            @Schema(description = "事实正确 0～2 分") int factual,
            @Schema(description = "引用支持 0～2 分；不适用时填 2 并在理由中说明") int citation,
            @Schema(description = "回答完整性 0～2 分") int completeness,
            @Schema(description = "回答可操作性 0～2 分") int actionability,
            @Schema(description = "表达清晰度 0～2 分") int clarity) {
        /** 保留未决结论的空值，已决时复制应用层最终评分。 */
        public static ScoreResponse from(Score value) {
            return value == null ? null : new ScoreResponse(value.factual(), value.citation(),
                    value.completeness(), value.actionability(), value.clarity());
        }
    }

    /** 当前管理员可见的单人盲评分。 */
    public record ReviewResponse(
            @Schema(description = "评审管理员 UUID") UUID reviewerId,
            @Schema(description = "FIRST 首评、SECOND 次评或 ARBITER 第三人复核") String role,
            @Schema(description = "事实、引用、完整、操作与清晰五维 0～2 分") ScoreResponse score,
            @Schema(description = "覆盖五维的人工评分理由") String reason,
            @Schema(description = "评分提交 UTC 时间") Instant createdAt) {
        /** 从应用层不可变评分创建安全响应。 */
        public static ReviewResponse from(Review value) {
            return new ReviewResponse(value.reviewerId(), value.role(), ScoreResponse.from(value.score()),
                    value.reason(), value.createdAt());
        }
    }
}
