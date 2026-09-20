package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.AuthenticationController.UserResponse;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露管理员创建用户和启停账号的阶段 11 接口。 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final UserAdminUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入用户管理用例和统一响应工厂。 */
    public AdminUserController(UserAdminUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 由管理员创建本地用户，响应不回显初始密码。 */
    @Operation(summary = "管理员创建本地用户")
    @PostMapping
    public ResponseEntity<ApiResult<UserResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateUserRequest body, HttpServletRequest request) {
        UserResponse result = UserResponse.from(useCase.create(actor, body.username(),
                body.displayName(), body.password(), body.role(), body.idempotencyKey()));
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.success(result, request));
    }

    /** 由管理员启用或禁用版本匹配的账号。 */
    @Operation(summary = "管理员启用或禁用用户")
    @PatchMapping("/{userId}/status")
    public ApiResult<UserResponse> changeStatus(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID userId, @Valid @RequestBody ChangeStatusRequest body,
            HttpServletRequest request) {
        return responses.success(UserResponse.from(useCase.changeStatus(actor, userId,
                body.status(), body.version())), request);
    }

    /** @param username 唯一登录名 @param displayName 展示名称 @param password 初始明文密码 @param role USER 或 ADMIN @param idempotencyKey 客户端创建操作幂等键 */
    public record CreateUserRequest(
            @NotBlank @Size(max = 64) @Schema(description = "唯一登录用户名") String username,
            @NotBlank @Size(max = 100) @Schema(description = "用户展示名称") String displayName,
            @NotBlank @Size(min = 12, max = 72) @Schema(description = "初始密码，仅当前请求使用", format = "password") String password,
            @NotNull @Schema(description = "用户角色：USER 或 ADMIN") UserRole role,
            @NotBlank @Size(max = 160) @Schema(description = "本次创建操作幂等键") String idempotencyKey) { }

    /** @param status 目标状态 @param version 客户端读取到的当前乐观锁版本 */
    public record ChangeStatusRequest(
            @NotNull @Schema(description = "目标状态：ACTIVE 或 DISABLED") UserStatus status,
            @NotNull @PositiveOrZero @Schema(description = "当前乐观锁版本") Long version) { }
}
