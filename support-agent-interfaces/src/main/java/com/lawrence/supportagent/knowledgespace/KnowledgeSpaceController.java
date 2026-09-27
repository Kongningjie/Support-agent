package com.lawrence.supportagent.knowledgespace;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.sharedkernel.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 暴露知识空间发现、治理与成员管理接口。 */
@RestController
public class KnowledgeSpaceController {
    private final KnowledgeSpaceUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入知识空间用例和统一响应工厂。 */
    public KnowledgeSpaceController(KnowledgeSpaceUseCase useCase,
                                    ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 查询当前用户可读空间；管理员可附带管理筛选条件。 */
    @Operation(summary = "分页查询知识空间")
    @GetMapping("/api/v1/knowledge-spaces")
    public ApiResult<PageResult<KnowledgeSpaceView>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) KnowledgeSpaceStatus status,
            @RequestParam(required = false) KnowledgeSpaceVisibility visibility,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        KnowledgeSpacePage result = useCase.list(actor, status, visibility, keyword, page, size);
        return responses.success(page(result.items(), result.page(), result.size(), result.total()), request);
    }

    /** 查询可见空间详情；无权的受限空间与不存在均响应 404。 */
    @Operation(summary = "查询知识空间详情")
    @GetMapping("/api/v1/knowledge-spaces/{spaceId}")
    public ApiResult<KnowledgeSpaceView> details(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            HttpServletRequest request) {
        return responses.success(useCase.details(actor, spaceId), request);
    }

    /** 由平台管理员创建默认受限空间。 */
    @Operation(summary = "管理员创建知识空间")
    @PostMapping("/api/v1/admin/knowledge-spaces")
    public ResponseEntity<ApiResult<KnowledgeSpaceView>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody CreateSpaceRequest body, HttpServletRequest request) {
        KnowledgeSpaceView value = useCase.create(actor, body.code(), body.name(),
                body.description(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.success(value, request));
    }

    /** 由平台管理员按版本修改普通空间展示信息和可见性。 */
    @Operation(summary = "管理员修改知识空间")
    @PatchMapping("/api/v1/admin/knowledge-spaces/{spaceId}")
    public ApiResult<KnowledgeSpaceView> update(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody UpdateSpaceRequest body, HttpServletRequest request) {
        return responses.success(useCase.update(actor, spaceId, body.name(), body.description(),
                body.visibility(), body.expectedVersion(), idempotencyKey), request);
    }

    /** 由平台管理员按版本停用普通空间。 */
    @Operation(summary = "管理员停用知识空间")
    @PostMapping("/api/v1/admin/knowledge-spaces/{spaceId}/disable")
    public ApiResult<KnowledgeSpaceView> disable(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return responses.success(useCase.disable(actor, spaceId, body.expectedVersion(),
                idempotencyKey), request);
    }

    /** 由平台管理员按版本重新启用普通空间。 */
    @Operation(summary = "管理员启用知识空间")
    @PostMapping("/api/v1/admin/knowledge-spaces/{spaceId}/enable")
    public ApiResult<KnowledgeSpaceView> enable(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return responses.success(useCase.enable(actor, spaceId, body.expectedVersion(),
                idempotencyKey), request);
    }

    /** 由空间 MANAGER 或平台管理员分页查询成员关系。 */
    @Operation(summary = "分页查询知识空间成员")
    @GetMapping("/api/v1/knowledge-spaces/{spaceId}/members")
    public ApiResult<PageResult<SpaceMembershipView>> members(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        SpaceMembershipPage result = useCase.members(actor, spaceId, page, size);
        return responses.success(page(result.items(), result.page(), result.size(), result.total()), request);
    }

    /** 新增、恢复或修改空间成员角色。 */
    @Operation(summary = "新增或修改知识空间成员")
    @PutMapping("/api/v1/knowledge-spaces/{spaceId}/members/{userId}")
    public ApiResult<SpaceMembershipView> putMember(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody PutMemberRequest body, HttpServletRequest request) {
        return responses.success(useCase.putMember(actor, spaceId, userId, body.role(),
                body.expectedVersion(), idempotencyKey), request);
    }

    /** 按版本撤销空间成员关系。 */
    @Operation(summary = "撤销知识空间成员")
    @DeleteMapping("/api/v1/knowledge-spaces/{spaceId}/members/{userId}")
    public ApiResult<SpaceMembershipView> revokeMember(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID spaceId,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 160) String idempotencyKey,
            @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return responses.success(useCase.revokeMember(actor, spaceId, userId,
                body.expectedVersion(), idempotencyKey), request);
    }

    /** 把应用层分页转换成统一 HTTP 分页结构。 */
    private <T> PageResult<T> page(java.util.List<T> items, int page, int size, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(items, page, size, total, totalPages);
    }

    /** @param code 稳定空间代码 @param name 展示名称 @param description 用途和知识边界说明 */
    public record CreateSpaceRequest(
            @NotBlank @Size(max = 64) @Schema(description = "企业内唯一、创建后不可修改的空间代码") String code,
            @NotBlank @Size(max = 100) @Schema(description = "用户可见空间名称") String name,
            @Size(max = 500) @Schema(description = "空间用途和知识边界说明") String description) { }

    /** @param name 展示名称 @param description 用途说明 @param visibility 读取可见性 @param expectedVersion 当前空间版本 */
    public record UpdateSpaceRequest(
            @NotBlank @Size(max = 100) @Schema(description = "用户可见空间名称") String name,
            @Size(max = 500) @Schema(description = "空间用途和知识边界说明") String description,
            @NotNull @Schema(description = "读取可见性：ENTERPRISE 或 RESTRICTED") KnowledgeSpaceVisibility visibility,
            @NotNull @PositiveOrZero @Schema(description = "客户端读取到的当前空间版本") Long expectedVersion) { }

    /** @param role 目标空间角色 @param expectedVersion 已有成员当前版本；新增成员必须为空 */
    public record PutMemberRequest(
            @NotNull @Schema(description = "目标空间角色：READER、EDITOR 或 MANAGER") SpaceRole role,
            @PositiveOrZero @Schema(description = "已有成员当前版本；新增成员不传") Long expectedVersion) { }

    /** @param expectedVersion 客户端读取到的当前资源版本 */
    public record VersionRequest(
            @NotNull @PositiveOrZero @Schema(description = "客户端读取到的当前资源版本") Long expectedVersion) { }
}
