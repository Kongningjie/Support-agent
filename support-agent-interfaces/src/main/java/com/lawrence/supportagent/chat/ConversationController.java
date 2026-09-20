package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort.Citation;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将会话生命周期 HTTP 契约适配到应用用例，不直接访问 Redis。 */
@Validated
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationLifecycleUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入会话生命周期用例和统一响应工厂。 */
    public ConversationController(ConversationLifecycleUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 按最近访问时间倒序分页查询当前用户自己的会话。 */
    @Operation(summary = "分页查询本人会话")
    @GetMapping
    public ApiResult<PageResult<ConversationResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "从 1 开始的页码", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @Parameter(description = "每页数量，范围 1～100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        ConversationPage result = useCase.list(actor, page, size);
        PageResult<ConversationResponse> data = new PageResult<>(
                result.items().stream().map(ConversationResponse::from).toList(), result.page(), result.size(),
                result.totalElements(), result.totalPages());
        return responses.success(data, request);
    }

    /** 查询本人会话详情；管理员可按明确 ID 查看其他用户会话。 */
    @Operation(summary = "查询会话详情")
    @GetMapping("/{conversationId}")
    public ApiResult<ConversationDetailsResponse> details(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "公开会话 UUID", example = "8e51b6d7-a9a9-4db1-b083-aec0fcfa3881")
            @PathVariable UUID conversationId, HttpServletRequest request) {
        return responses.success(ConversationDetailsResponse.from(useCase.details(actor, conversationId)), request);
    }

    /** 在版本一致且没有活动运行时原子重置本人会话。 */
    @Operation(summary = "重置本人会话")
    @PostMapping("/{conversationId}/reset")
    public ApiResult<ConversationResponse> reset(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "公开会话 UUID", example = "8e51b6d7-a9a9-4db1-b083-aec0fcfa3881")
            @PathVariable UUID conversationId,
            @Valid @RequestBody ResetConversationRequest body,
            HttpServletRequest request) {
        return responses.success(ConversationResponse.from(
                useCase.reset(actor, conversationId, body.expectedVersion())), request);
    }

    /** 在版本一致且没有活动运行时原子删除本人会话。 */
    @Operation(summary = "删除本人会话")
    @DeleteMapping("/{conversationId}")
    public ApiResult<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "公开会话 UUID", example = "8e51b6d7-a9a9-4db1-b083-aec0fcfa3881")
            @PathVariable UUID conversationId,
            @Parameter(description = "客户端持有的非负会话版本", example = "3")
            @RequestParam @PositiveOrZero long expectedVersion,
            HttpServletRequest request) {
        useCase.delete(actor, conversationId, expectedVersion);
        return responses.success(null, request);
    }

    /**
     * @param expectedVersion 客户端持有的当前会话版本
     */
    public record ResetConversationRequest(
            @NotNull @PositiveOrZero
            @Schema(description = "客户端持有的非负会话版本", example = "3",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Long expectedVersion) { }

    /**
     * @param conversationId 公开会话 UUID
     * @param status 当前状态，仅为 IDLE 或 RUNNING
     * @param version 已成功提交的轮次版本
     * @param generation 同一会话 ID 的重置代次
     * @param summaryVersion 滚动摘要修订版本
     * @param lastAccessAt 最近一次成功会话活动的 UTC 时间
     * @param expiresAt 按 Redis TTL 推算的预计 UTC 过期时间
     */
    public record ConversationResponse(
            @Schema(description = "公开会话 UUID", example = "8e51b6d7-a9a9-4db1-b083-aec0fcfa3881")
            UUID conversationId,
            @Schema(description = "当前运行状态，仅为 IDLE 或 RUNNING", example = "IDLE")
            ConversationLifecycleStatus status,
            @Schema(description = "已成功提交的轮次版本，从 0 开始", example = "3")
            long version,
            @Schema(description = "同一会话 ID 的重置代次，首次为 0", example = "1")
            long generation,
            @Schema(description = "滚动摘要修订版本，尚无摘要时为 0", example = "2")
            long summaryVersion,
            @Schema(description = "最近一次成功会话活动的 UTC 时间", example = "2026-09-20T08:00:00Z")
            Instant lastAccessAt,
            @Schema(description = "按当前 Redis TTL 推算的预计 UTC 过期时间", example = "2026-09-27T08:00:00Z")
            Instant expiresAt) {
        /** 将应用层会话元数据转换为 HTTP 响应。 */
        public static ConversationResponse from(ConversationOverview value) {
            return new ConversationResponse(value.conversationId(), value.status(), value.version(),
                    value.generation(), value.summaryVersion(), value.lastAccessAt(), value.expiresAt());
        }
    }

    /**
     * @param conversation 会话公开元数据
     * @param recentTurns Redis 当前仍保留的最近成功轮次
     */
    public record ConversationDetailsResponse(
            @Schema(description = "会话公开元数据") ConversationResponse conversation,
            @Schema(description = "最近成功轮次；不包含 Agent 内部状态和建议冻结上下文")
            List<ConversationTurnResponse> recentTurns) {
        /** 将应用层详情转换为 HTTP 响应。 */
        public static ConversationDetailsResponse from(ConversationDetails value) {
            return new ConversationDetailsResponse(ConversationResponse.from(value.overview()),
                    value.recentTurns().stream().map(ConversationTurnResponse::from).toList());
        }
    }

    /**
     * @param turnId 成功轮次 UUID
     * @param userMessage 用户输入正文
     * @param answer 已通过安全校验的完整回答
     * @param intent 最终意图
     * @param retrievalStatus 检索结果三态；非技术支持回答可为空
     * @param citations 最终公开引用
     * @param completedAt 轮次完成 UTC 时间
     * @param conversationVersion 本轮提交后的会话版本
     */
    public record ConversationTurnResponse(
            @Schema(description = "成功轮次 UUID") UUID turnId,
            @Schema(description = "用户输入正文") String userMessage,
            @Schema(description = "已通过安全校验的完整回答") String answer,
            @Schema(description = "最终意图", example = "SUPPORT_QUERY") ChatIntent intent,
            @Schema(description = "检索结果三态；非技术支持回答可为空", nullable = true)
            RetrievalStatus retrievalStatus,
            @Schema(description = "最终公开引用") List<CitationResponse> citations,
            @Schema(description = "轮次完成 UTC 时间", example = "2026-09-20T08:00:00Z") Instant completedAt,
            @Schema(description = "本轮提交后的会话版本", example = "3") long conversationVersion) {
        /** 将应用层轮次转换为不包含内部状态的 HTTP 响应。 */
        public static ConversationTurnResponse from(ConversationTurnView value) {
            return new ConversationTurnResponse(value.turnId(), value.userMessage(), value.answer(), value.intent(),
                    value.retrievalStatus(), value.citations().stream().map(CitationResponse::from).toList(),
                    value.completedAt(), value.conversationVersion());
        }
    }

    /**
     * @param citationId 回答正文使用的临时引用标识
     * @param documentId 来源文档或案例标识
     * @param documentTitle 来源标题
     * @param headingPath 来源标题路径
     * @param sourceType 来源类型
     * @param sourceCaseId 案例来源公开 ID；非案例来源为空
     */
    public record CitationResponse(
            @Schema(description = "回答正文使用的临时引用标识", example = "S1") String citationId,
            @Schema(description = "来源文档或案例标识") String documentId,
            @Schema(description = "来源标题") String documentTitle,
            @Schema(description = "来源标题路径") String headingPath,
            @Schema(description = "来源类型", example = "MANAGED_DOCUMENT") String sourceType,
            @Schema(description = "案例来源公开 ID；非案例来源为空", nullable = true) String sourceCaseId) {
        /** 将会话存储引用转换为 HTTP 响应。 */
        public static CitationResponse from(Citation value) {
            return new CitationResponse(value.citationId(), value.documentId(), value.documentTitle(),
                    value.headingPath(), value.sourceType(), value.sourceCaseId());
        }
    }
}
