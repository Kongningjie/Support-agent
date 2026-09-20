package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.auth.AuthenticatedUser;
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
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 将本人长期记忆管理契约适配到应用用例，不直接访问 MySQL 或模型。 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class UserMemoryController {
    private final UserMemoryUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入长期记忆用例和统一响应工厂。 */
    public UserMemoryController(UserMemoryUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 查询本人长期记忆开关；首次查询默认关闭且版本为零。 */
    @Operation(summary = "查询本人长期记忆开关")
    @GetMapping("/users/me/memory-settings")
    public ApiResult<MemorySettingsResponse> settings(
            @AuthenticationPrincipal AuthenticatedUser actor, HttpServletRequest request) {
        return responses.success(MemorySettingsResponse.from(useCase.settings(actor)), request);
    }

    /** 以乐观锁和幂等键修改本人长期记忆开关。 */
    @Operation(summary = "修改本人长期记忆开关")
    @PatchMapping("/users/me/memory-settings")
    public ApiResult<MemorySettingsResponse> updateSettings(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "外部幂等键，最长 160 字符", required = true)
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody UpdateMemorySettingsRequest body,
            HttpServletRequest request) {
        return responses.success(MemorySettingsResponse.from(useCase.updateSettings(actor,
                body.enabled(), body.expectedVersion(), idempotencyKey)), request);
    }

    /** 分页查询本人记忆；管理员也只能通过该接口读取自己的正文。 */
    @Operation(summary = "分页查询本人长期记忆")
    @GetMapping("/memories")
    public ApiResult<PageResult<MemoryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "可选生命周期状态过滤") @RequestParam(required = false) MemoryStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        UserMemoryPage result = useCase.list(actor, status, page, size);
        return responses.success(new PageResult<>(result.items().stream()
                .map(MemoryResponse::from).toList(), result.page(), result.size(),
                result.totalElements(), result.totalPages()), request);
    }

    /** 确认本人候选并允许其在预算内注入上下文。 */
    @Operation(summary = "确认长期记忆候选")
    @PostMapping("/memories/{memoryId}/confirm")
    public ApiResult<MemoryResponse> confirm(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID memoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return responses.success(MemoryResponse.from(useCase.confirm(actor, memoryId,
                body.expectedVersion(), idempotencyKey)), request);
    }

    /** 更正本人记忆正文、过期时间或固定标志。 */
    @Operation(summary = "更正本人长期记忆")
    @PatchMapping("/memories/{memoryId}")
    public ApiResult<MemoryResponse> revise(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID memoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody ReviseMemoryRequest body, HttpServletRequest request) {
        return responses.success(MemoryResponse.from(useCase.revise(actor, memoryId,
                body.content(), body.expiresAt(), body.clearExpiresAt(), body.pinned(),
                body.expectedVersion(), idempotencyKey)), request);
    }

    /** 撤销本人记忆并立即取消其注入资格。 */
    @Operation(summary = "撤销本人长期记忆")
    @PostMapping("/memories/{memoryId}/revoke")
    public ApiResult<MemoryResponse> revoke(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID memoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return responses.success(MemoryResponse.from(useCase.revoke(actor, memoryId,
                body.expectedVersion(), idempotencyKey)), request);
    }

    /** 永久删除本人记忆；成功后正文和审计字段均不再保留。 */
    @Operation(summary = "永久删除本人长期记忆")
    @DeleteMapping("/memories/{memoryId}")
    public ApiResult<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID memoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @RequestParam @PositiveOrZero long expectedVersion, HttpServletRequest request) {
        useCase.delete(actor, memoryId, expectedVersion, idempotencyKey);
        return responses.success(null, request);
    }

    /** @param enabled 是否允许生成和注入长期记忆 @param expectedVersion 当前设置版本 */
    public record UpdateMemorySettingsRequest(
            @Schema(description = "是否允许生成和注入长期记忆", example = "true") boolean enabled,
            @NotNull @PositiveOrZero @Schema(description = "当前设置乐观锁版本", example = "0")
            Long expectedVersion) { }

    /** @param expectedVersion 客户端持有的当前记忆版本 */
    public record VersionRequest(
            @NotNull @PositiveOrZero @Schema(description = "当前记忆乐观锁版本", example = "0")
            Long expectedVersion) { }

    /**
     * @param content 可选新正文；空值表示保持原正文，空字符串不合法
     * @param expiresAt 可选新失效时间；空值且 clearExpiresAt=false 表示保持原值
     * @param clearExpiresAt 是否明确删除已有失效时间
     * @param pinned 可选固定标志；空值表示保持原值
     * @param expectedVersion 当前记忆版本
     */
    public record ReviseMemoryRequest(
            @Size(min = 1, max = 500) @Schema(description = "可选新正文；空值表示保持原正文")
            String content,
            @Schema(description = "可选新 UTC 失效时间；必须晚于当前时间", nullable = true)
            Instant expiresAt,
            @Schema(description = "是否明确清除已有失效时间", example = "false")
            boolean clearExpiresAt,
            @Schema(description = "可选固定标志；空值表示保持原值", nullable = true)
            Boolean pinned,
            @NotNull @PositiveOrZero @Schema(description = "当前记忆乐观锁版本", example = "1")
            Long expectedVersion) { }

    /** @param enabled 是否允许生成和注入长期记忆 @param version 设置乐观锁版本 */
    public record MemorySettingsResponse(
            @Schema(description = "是否允许生成和注入长期记忆", example = "false") boolean enabled,
            @Schema(description = "设置乐观锁版本；从 0 开始", example = "0") long version) {
        /** 将应用设置视图转换为 HTTP 响应。 */
        public static MemorySettingsResponse from(MemorySettingsView value) {
            return new MemorySettingsResponse(value.enabled(), value.version());
        }
    }

    /**
     * @param memoryId 公开记忆 UUID
     * @param memoryType 记忆类别
     * @param content 用户可见正文
     * @param status 候选、有效或已撤销状态
     * @param pinned 是否由用户显式固定
     * @param sourceConversationId 候选来源会话 UUID
     * @param sourceTurnId 候选来源客户端消息 UUID
     * @param expiresAt 可选 UTC 失效时间
     * @param version 记忆乐观锁版本
     * @param createdAt 候选创建 UTC 时间
     * @param updatedAt 最近修改 UTC 时间
     */
    public record MemoryResponse(
            @Schema(description = "公开记忆 UUID") UUID memoryId,
            @Schema(description = "记忆类别：PREFERENCE、CONSTRAINT 或 ENVIRONMENT") MemoryType memoryType,
            @Schema(description = "用户可见的简短记忆正文，最多 500 字符") String content,
            @Schema(description = "生命周期状态：PROPOSED、ACTIVE 或 REVOKED") MemoryStatus status,
            @Schema(description = "是否由用户显式固定并优先注入") boolean pinned,
            @Schema(description = "候选来源公开会话 UUID") UUID sourceConversationId,
            @Schema(description = "候选来源客户端消息 UUID") UUID sourceTurnId,
            @Schema(description = "可选 UTC 失效时间；到期后不再注入", nullable = true) Instant expiresAt,
            @Schema(description = "乐观锁版本，从 0 开始") long version,
            @Schema(description = "候选创建 UTC 时间") Instant createdAt,
            @Schema(description = "最近修改 UTC 时间") Instant updatedAt) {
        /** 将应用记忆视图转换为 HTTP 响应。 */
        public static MemoryResponse from(UserMemoryView value) {
            return new MemoryResponse(value.memoryId(), value.memoryType(), value.content(),
                    value.status(), value.pinned(), value.sourceConversationId(),
                    value.sourceTurnId(), value.expiresAt(), value.version(),
                    value.createdAt(), value.updatedAt());
        }
    }
}
