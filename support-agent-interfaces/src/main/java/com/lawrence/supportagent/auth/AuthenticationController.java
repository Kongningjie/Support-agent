package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露本地登录、当前 Token 注销和本人信息接口。 */
@RestController
@RequestMapping("/api/v1")
public class AuthenticationController {
    private static final String BEARER = "Bearer ";
    private final AuthenticationUseCase useCase;
    private final ApiResponseFactory responses;

    /** 注入认证用例和统一响应工厂。 */
    public AuthenticationController(AuthenticationUseCase useCase, ApiResponseFactory responses) {
        this.useCase = useCase;
        this.responses = responses;
    }

    /** 校验本地账号并返回一次性原始 Token。 */
    @Operation(summary = "本地用户名密码登录")
    @PostMapping("/auth/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest body,
                                          HttpServletRequest request) {
        LoginResult result = useCase.login(body.username(), body.password(), request.getRemoteAddr());
        return responses.success(LoginResponse.from(result), request);
    }

    /** 幂等撤销当前请求使用的 Token。 */
    @Operation(summary = "注销当前登录 Token")
    @PostMapping("/auth/logout")
    public ApiResult<Void> logout(HttpServletRequest request) {
        useCase.logout(rawToken(request));
        return responses.success(null, request);
    }

    /** 查询当前已认证用户的安全摘要。 */
    @Operation(summary = "查询当前用户")
    @GetMapping("/users/me")
    public ApiResult<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser actor,
                                      HttpServletRequest request) {
        return responses.success(UserResponse.from(useCase.me(actor)), request);
    }

    /** 校验当前密码并更新为新密码；成功后全部旧 Token 失效。 */
    @Operation(summary = "修改本人密码并撤销全部旧 Token")
    @PostMapping("/users/me/password")
    public ApiResult<UserResponse> changePassword(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        return responses.success(UserResponse.from(useCase.changePassword(actor,
                body.oldPassword(), body.newPassword())), request);
    }

    /** 撤销本人全部有效 Token，包括当前请求使用的 Token。 */
    @Operation(summary = "撤销本人全部登录 Token")
    @PostMapping("/users/me/tokens/revoke-all")
    public ApiResult<Void> revokeAll(@AuthenticationPrincipal AuthenticatedUser actor,
                                     HttpServletRequest request) {
        useCase.revokeAll(actor);
        return responses.success(null, request);
    }

    /** 从已经通过过滤器认证的 Header 提取原始 Token，仅用于当前注销。 */
    private String rawToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER) || header.length() <= BEARER.length()) {
            throw new IllegalArgumentException("缺少 Bearer Token");
        }
        return header.substring(BEARER.length()).strip();
    }

    /** @param username 本地登录用户名 @param password 本次登录明文密码 */
    public record LoginRequest(
            @NotBlank @Size(max = 64) @Schema(description = "本地登录用户名") String username,
            @NotBlank @Size(min = 12, max = 72) @Schema(description = "本次登录密码", format = "password") String password) { }

    /** @param oldPassword 当前密码 @param newPassword 待设置且不得与当前密码相同的新密码 */
    public record ChangePasswordRequest(
            @NotBlank @Size(min = 12, max = 72) @Schema(description = "当前密码", format = "password") String oldPassword,
            @NotBlank @Size(min = 12, max = 72) @Schema(description = "新密码", format = "password") String newPassword) { }

    /** @param accessToken 原始不透明 Token @param tokenType 固定 Bearer @param expiresAt UTC 失效时间 @param user 用户摘要 */
    public record LoginResponse(
            @Schema(description = "仅返回一次的原始不透明访问 Token") String accessToken,
            @Schema(description = "HTTP 认证方案，固定为 Bearer") String tokenType,
            @Schema(description = "Token 绝对失效 UTC 时间") java.time.Instant expiresAt,
            @Schema(description = "当前用户安全摘要") UserResponse user) {
        /** 从应用登录结果创建接口响应。 */
        public static LoginResponse from(LoginResult value) {
            return new LoginResponse(value.accessToken(), value.tokenType(), value.expiresAt(),
                    UserResponse.from(value.user()));
        }
    }

    /** @param userId 公开用户 UUID @param username 登录名 @param displayName 展示名称 @param role 两级角色 @param status 账号状态 @param version 乐观锁版本 @param mustChangePassword 是否必须先改密 @param lockedUntil 临时锁定截止时间 @param createdAt 创建时间 @param updatedAt 更新时间 */
    public record UserResponse(
            @Schema(description = "用户公开 UUID") java.util.UUID userId,
            @Schema(description = "规范化小写登录名") String username,
            @Schema(description = "用户展示名称") String displayName,
            @Schema(description = "用户角色：USER 或 ADMIN") com.lawrence.supportagent.user.UserRole role,
            @Schema(description = "账号状态：ACTIVE 或 DISABLED") com.lawrence.supportagent.user.UserStatus status,
            @Schema(description = "账号乐观锁版本") long version,
            @Schema(description = "是否必须先修改管理员设置的一次性密码") boolean mustChangePassword,
            @Schema(description = "临时锁定截止 UTC 时间；未锁定时为空", nullable = true) java.time.Instant lockedUntil,
            @Schema(description = "账号创建 UTC 时间") java.time.Instant createdAt,
            @Schema(description = "账号最近更新 UTC 时间") java.time.Instant updatedAt) {
        /** 从应用用户视图创建接口响应。 */
        public static UserResponse from(UserView value) {
            return new UserResponse(value.userId(), value.username(), value.displayName(), value.role(),
                    value.status(), value.version(), value.mustChangePassword(), value.lockedUntil(),
                    value.createdAt(), value.updatedAt());
        }
    }
}
