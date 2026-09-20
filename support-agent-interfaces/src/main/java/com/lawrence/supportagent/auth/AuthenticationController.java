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

    /** @param accessToken 原始不透明 Token @param tokenType 固定 Bearer @param expiresAt UTC 失效时间 @param user 用户摘要 */
    public record LoginResponse(String accessToken, String tokenType, java.time.Instant expiresAt,
                                UserResponse user) {
        /** 从应用登录结果创建接口响应。 */
        public static LoginResponse from(LoginResult value) {
            return new LoginResponse(value.accessToken(), value.tokenType(), value.expiresAt(),
                    UserResponse.from(value.user()));
        }
    }

    /** @param userId 公开用户 UUID @param username 登录名 @param displayName 展示名称 @param role 两级角色 @param status 账号状态 @param version 乐观锁版本 @param createdAt 创建时间 @param updatedAt 更新时间 */
    public record UserResponse(java.util.UUID userId, String username, String displayName,
                               com.lawrence.supportagent.user.UserRole role,
                               com.lawrence.supportagent.user.UserStatus status, long version,
                               java.time.Instant createdAt, java.time.Instant updatedAt) {
        /** 从应用用户视图创建接口响应。 */
        public static UserResponse from(UserView value) {
            return new UserResponse(value.userId(), value.username(), value.displayName(), value.role(),
                    value.status(), value.version(), value.createdAt(), value.updatedAt());
        }
    }
}
