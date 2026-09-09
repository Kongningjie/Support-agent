package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将已解决案例查询和人工审核动作适配为 REST API。 */
@Validated
@RestController
@RequestMapping("/api/v1/resolved-cases")
public class ResolvedCaseController {
    private final ResolvedCaseQueryUseCase queries;
    private final ResolvedCaseCommandUseCase commands;
    private final ApiResponseFactory responses;

    /** 注入案例查询、命令用例和统一响应工厂。 */
    public ResolvedCaseController(ResolvedCaseQueryUseCase queries,
                                  ResolvedCaseCommandUseCase commands,
                                  ApiResponseFactory responses) {
        this.queries = queries;
        this.commands = commands;
        this.responses = responses;
    }

    /** 查询包含来源工单摘要的完整案例。 */
    @Operation(summary = "查询已解决案例详情")
    @GetMapping("/{caseId}")
    public ApiResult<CaseResponse> get(
            @Parameter(description = "案例内部 ID 的字符串形式", example = "1")
            @PathVariable long caseId, HttpServletRequest request) {
        return responses.success(CaseResponse.from(queries.get(caseId)), request);
    }

    /** 按状态、来源工单编号和关键词分页查询案例摘要。 */
    @Operation(summary = "分页查询已解决案例")
    @GetMapping
    public ApiResult<PageResult<CaseSummaryResponse>> page(
            @RequestParam(required = false) ResolvedCaseStatus status,
            @RequestParam(required = false) String sourceTicketNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        ResolvedCasePage result = queries.page(status, sourceTicketNo, keyword, page, size);
        return responses.success(new PageResult<>(result.items().stream()
                        .map(CaseSummaryResponse::from).toList(), result.page(), result.size(),
                result.totalElements(), result.totalPages()), request);
    }

    /** 人工修改案例草稿或发布失败内容。 */
    @Operation(summary = "修改案例待审核内容")
    @PutMapping("/{caseId}/draft")
    public ApiResult<CaseResponse> revise(@PathVariable long caseId,
                                          @Valid @RequestBody ReviseRequest body,
                                          HttpServletRequest request) {
        return responses.success(CaseResponse.from(commands.revise(caseId, body.title(),
                body.problem(), body.cause(), body.solution(), body.version())), request);
    }

    /** 人工审核通过并异步发布案例。 */
    @Operation(summary = "发布已解决案例")
    @PostMapping("/{caseId}/publish")
    public ApiResult<CaseResponse> publish(@PathVariable long caseId,
                                           @Valid @RequestBody VersionedRequest body,
                                           HttpServletRequest request) {
        return responses.success(CaseResponse.from(commands.publish(caseId, body.version(),
                body.idempotencyKey())), request);
    }

    /** 人工决定案例不进入知识库并永久拒绝。 */
    @Operation(summary = "拒绝已解决案例")
    @PostMapping("/{caseId}/reject")
    public ApiResult<CaseResponse> reject(@PathVariable long caseId,
                                          @Valid @RequestBody RejectRequest body,
                                          HttpServletRequest request) {
        return responses.success(CaseResponse.from(commands.reject(caseId, body.rejectionReason(),
                body.version(), body.idempotencyKey())), request);
    }

    /** 归档已发布案例并异步移除检索分块。 */
    @Operation(summary = "归档已解决案例")
    @PostMapping("/{caseId}/archive")
    public ApiResult<CaseResponse> archive(@PathVariable long caseId,
                                           @Valid @RequestBody ArchiveRequest body,
                                           HttpServletRequest request) {
        return responses.success(CaseResponse.from(commands.archive(caseId, body.archiveReason(),
                body.version(), body.idempotencyKey())), request);
    }

    /** @param title 标题 @param problem 问题 @param cause 根因 @param solution 方案 @param version 当前版本 */
    public record ReviseRequest(
            @NotBlank @Size(max = 160) @Schema(description = "审核后的案例标题") String title,
            @NotBlank @Size(max = 4000) @Schema(description = "审核后的问题现象和背景") String problem,
            @NotBlank @Size(max = 4000) @Schema(description = "审核人确认的根因") String cause,
            @NotBlank @Size(max = 8000) @Schema(description = "审核人确认的解决步骤") String solution,
            @NotNull @PositiveOrZero @Schema(description = "当前乐观锁版本") Long version) { }

    /** @param version 当前版本 @param idempotencyKey 本次动作幂等键 */
    public record VersionedRequest(
            @NotNull @PositiveOrZero @Schema(description = "当前乐观锁版本") Long version,
            @NotBlank @Size(max = 160) @Schema(description = "本次发布操作幂等键") String idempotencyKey) { }

    /** @param rejectionReason 拒绝原因 @param version 当前版本 @param idempotencyKey 本次动作幂等键 */
    public record RejectRequest(
            @NotBlank @Size(max = 500) @Schema(description = "人工填写的拒绝原因") String rejectionReason,
            @NotNull @PositiveOrZero @Schema(description = "当前乐观锁版本") Long version,
            @NotBlank @Size(max = 160) @Schema(description = "本次拒绝操作幂等键") String idempotencyKey) { }

    /** @param archiveReason 归档原因 @param version 当前版本 @param idempotencyKey 本次动作幂等键 */
    public record ArchiveRequest(
            @NotBlank @Size(max = 500) @Schema(description = "人工填写的归档原因") String archiveReason,
            @NotNull @PositiveOrZero @Schema(description = "当前乐观锁版本") Long version,
            @NotBlank @Size(max = 160) @Schema(description = "本次归档操作幂等键") String idempotencyKey) { }

    /** @param caseId 案例 ID @param sourceTicketNo 工单号 @param title 标题 @param status 状态
     * @param version 版本 @param createdAt 创建时间 @param updatedAt 更新时间 */
    public record CaseSummaryResponse(
            @Schema(description = "案例内部 ID 的字符串形式", example = "1") String caseId,
            @Schema(description = "来源工单的稳定编号", example = "T000000000001") String sourceTicketNo,
            @Schema(description = "案例标题", example = "Spring Boot 连接 MySQL 端口错误") String title,
            @Schema(description = "案例生命周期状态", example = "DRAFT") ResolvedCaseStatus status,
            @Schema(description = "案例乐观锁和知识版本", example = "0") long version,
            @Schema(description = "案例创建 UTC 时间") Instant createdAt,
            @Schema(description = "案例最近更新 UTC 时间") Instant updatedAt) {
        /** 从应用摘要创建接口响应。 */
        public static CaseSummaryResponse from(ResolvedCaseSummary value) {
            return new CaseSummaryResponse(Long.toString(value.caseId()), value.sourceTicketNo(),
                    value.title(), value.status(), value.version(), value.createdAt(), value.updatedAt());
        }
    }

    /**
     * 案例完整响应，字段名称与领域字典一致。
     *
     * @param caseId 案例 ID @param sourceTicketNo 来源工单号 @param sourceTicketTitle 来源工单标题
     * @param title 案例标题 @param problem 问题描述 @param cause 根因 @param solution 解决方案
     * @param status 状态 @param version 版本 @param publishFailureReason 发布失败原因
     * @param rejectionReason 拒绝原因 @param archiveReason 归档原因 @param createdAt 创建时间
     * @param updatedAt 更新时间 @param publishedAt 发布时间 @param archivedAt 归档时间
     */
    public record CaseResponse(
            @Schema(description = "案例内部 ID 的字符串形式", example = "1") String caseId,
            @Schema(description = "来源工单稳定编号", example = "T000000000001") String sourceTicketNo,
            @Schema(description = "来源工单标题") String sourceTicketTitle,
            @Schema(description = "人工审核后的案例标题") String title,
            @Schema(description = "问题现象和适用背景") String problem,
            @Schema(description = "人工确认或审核修正后的根因") String cause,
            @Schema(description = "实际验证或审核修正后的解决方案") String solution,
            @Schema(description = "案例生命周期状态", example = "PUBLISHED") ResolvedCaseStatus status,
            @Schema(description = "案例乐观锁和知识版本") long version,
            @Schema(description = "发布任务最终失败的脱敏原因", nullable = true) String publishFailureReason,
            @Schema(description = "人工拒绝原因，仅 REJECTED 有值", nullable = true) String rejectionReason,
            @Schema(description = "退出检索的归档原因，仅 ARCHIVED 有值", nullable = true) String archiveReason,
            @Schema(description = "创建 UTC 时间") Instant createdAt,
            @Schema(description = "最近更新 UTC 时间") Instant updatedAt,
            @Schema(description = "成功发布 UTC 时间", nullable = true) Instant publishedAt,
            @Schema(description = "归档 UTC 时间", nullable = true) Instant archivedAt) {
        /** 从应用详情创建接口响应并把内部 ID 转为字符串。 */
        public static CaseResponse from(ResolvedCaseDetails value) {
            return new CaseResponse(Long.toString(value.caseId()), value.sourceTicketNo(),
                    value.sourceTicketTitle(), value.title(), value.problem(), value.cause(),
                    value.solution(), value.status(), value.version(), value.publishFailureReason(),
                    value.rejectionReason(), value.archiveReason(), value.createdAt(),
                    value.updatedAt(), value.publishedAt(), value.archivedAt());
        }
    }
}
