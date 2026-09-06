package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将异步任务运维查询和人工重试协议适配到应用用例。 */
@Validated
@RestController
@RequestMapping("/api/v1/async-tasks")
public class AsyncTaskController {
    private final AsyncTaskUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入异步任务用例和统一响应工厂。 */
    public AsyncTaskController(AsyncTaskUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 按十进制任务 ID 查询不含 Worker 锁字段的详情。 */
    @Operation(summary = "查询异步任务详情")
    @GetMapping("/{taskId}")
    public ApiResult<AsyncTaskResponse> get(
                                             @Parameter(description = "正整数任务 ID 的字符串形式",
                                                     example = "42")
                                             @PathVariable String taskId,
                                             HttpServletRequest request) {
        return responses.success(AsyncTaskResponse.from(useCase.get(parseId(taskId))), request);
    }

    /** 按受控过滤条件和固定排序查询一页异步任务。 */
    @Operation(summary = "分页查询异步任务")
    @GetMapping
    public ApiResult<PageResult<AsyncTaskResponse>> page(
            @Parameter(description = "可空任务类型过滤条件", example = "KNOWLEDGE_INDEX")
            @RequestParam(required = false) AsyncTaskType taskType,
            @Parameter(description = "可空任务状态过滤条件", example = "DEAD")
            @RequestParam(required = false) AsyncTaskStatus status,
            @Parameter(description = "可空关联聚合类型过滤条件", example = "MANAGED_DOCUMENT")
            @RequestParam(required = false) AggregateType aggregateType,
            @Parameter(description = "可空正整数关联聚合 ID 字符串", example = "15")
            @RequestParam(required = false) String aggregateId,
            @Parameter(description = "从 1 开始的页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量，范围 1～100", example = "20")
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long parsedAggregateId = aggregateId == null ? null : parseId(aggregateId);
        AsyncTaskPage result = useCase.page(taskType, status, aggregateType,
                parsedAggregateId, page, size);
        List<AsyncTaskResponse> items = result.items().stream()
                .map(AsyncTaskResponse::from).toList();
        return responses.success(new PageResult<>(items, result.page(), result.size(),
                result.totalElements(), result.totalPages()), request);
    }

    /** 为仍适用于原业务状态和版本的死亡任务创建新任务。 */
    @Operation(summary = "人工重试死亡任务")
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<ApiResult<AsyncTaskResponse>> retry(
            @Parameter(description = "待重试的死亡任务 ID", example = "42")
            @PathVariable String taskId, @Valid @RequestBody RetryTaskRequest body,
            HttpServletRequest request) {
        AsyncTaskDetails created = useCase.retry(parseId(taskId), body.reason(), body.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.success(AsyncTaskResponse.from(created), request));
    }

    /** 把 API 字符串 ID 严格解析为正 long，避免 JavaScript 精度问题。 */
    private long parseId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException("非正数");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("任务或关联资源 ID 必须为正整数");
        }
    }

    /**
     * 人工重试死亡任务请求。
     *
     * @param idempotencyKey 本次人工重试操作幂等键，1～160 字符
     * @param reason 人工确认重试的原因，1～500 字符
     */
    public record RetryTaskRequest(
            @Schema(description = "必填的人工重试操作幂等键，1～160 字符",
                    example = "task-retry-20260904-001")
            @NotBlank @Size(max = 160) String idempotencyKey,
            @Schema(description = "必填的人工确认重试原因，1～500 字符", example = "依赖服务已恢复")
            @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * 不包含 Worker 标识和锁租约的异步任务响应。
     *
     * @param taskId 任务 ID 字符串
     * @param taskType 任务类型
     * @param aggregateType 关联聚合类型
     * @param aggregateId 关联聚合 ID 字符串
     * @param aggregateVersion 创建任务时聚合版本
     * @param status 当前任务状态
     * @param attemptCount 已执行次数
     * @param maxAttempts 最大执行次数
     * @param nextRunAt 下次允许执行时间
     * @param lastErrorCode 最近失败错误码，可为空
     * @param lastErrorMessage 最近失败脱敏摘要，可为空
     * @param retryOfTaskId 人工重试来源任务 ID，可为空
     * @param manualRetryReason 人工重试原因，可为空
     * @param createdAt 创建时间
     * @param startedAt 首次开始时间，可为空
     * @param finishedAt 最终结束时间，可为空
     * @param updatedAt 最近更新时间
     */
    public record AsyncTaskResponse(
            @Schema(description = "异步任务内部 ID 的字符串形式", example = "42") String taskId,
            @Schema(description = "任务类型", example = "KNOWLEDGE_INDEX") AsyncTaskType taskType,
            @Schema(description = "关联聚合类型", example = "MANAGED_DOCUMENT") AggregateType aggregateType,
            @Schema(description = "关联聚合 ID 的字符串形式", example = "15") String aggregateId,
            @Schema(description = "任务创建时的聚合版本", example = "2") long aggregateVersion,
            @Schema(description = "任务执行状态", example = "DEAD") AsyncTaskStatus status,
            @Schema(description = "已经开始执行的次数", example = "3") int attemptCount,
            @Schema(description = "最大执行次数", example = "3") int maxAttempts,
            @Schema(description = "下一次允许调度的 UTC 时间",
                    example = "2026-09-04T01:10:00Z") Instant nextRunAt,
            @Schema(description = "最近失败稳定错误码；可以为空", example = "DASHSCOPE_TIMEOUT",
                    nullable = true) String lastErrorCode,
            @Schema(description = "最近失败脱敏摘要；可以为空", example = "模型服务暂时不可用",
                    nullable = true) String lastErrorMessage,
            @Schema(description = "人工重试所依据的原任务 ID；可以为空", example = "41",
                    nullable = true) String retryOfTaskId,
            @Schema(description = "人工发起重试的原因；可以为空", example = "依赖服务已恢复",
                    nullable = true) String manualRetryReason,
            @Schema(description = "任务创建 UTC 时间", example = "2026-09-04T01:00:00Z") Instant createdAt,
            @Schema(description = "任务首次开始 UTC 时间；可以为空",
                    example = "2026-09-04T01:01:00Z", nullable = true) Instant startedAt,
            @Schema(description = "任务最终结束 UTC 时间；可以为空",
                    example = "2026-09-04T01:02:00Z", nullable = true) Instant finishedAt,
            @Schema(description = "任务最近更新 UTC 时间",
                    example = "2026-09-04T01:02:00Z") Instant updatedAt) {
        /** 从应用层安全视图创建接口响应。 */
        public static AsyncTaskResponse from(AsyncTaskDetails value) {
            return new AsyncTaskResponse(value.taskId(), value.taskType(), value.aggregateType(),
                    value.aggregateId(), value.aggregateVersion(), value.status(),
                    value.attemptCount(), value.maxAttempts(), value.nextRunAt(),
                    value.lastErrorCode(), value.lastErrorMessage(), value.retryOfTaskId(),
                    value.manualRetryReason(), value.createdAt(), value.startedAt(),
                    value.finishedAt(), value.updatedAt());
        }
    }
}
