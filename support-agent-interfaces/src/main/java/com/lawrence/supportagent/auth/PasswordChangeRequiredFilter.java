package com.lawrence.supportagent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 限制一次性密码登录取得的 Token 只能完成本人查询、改密和注销。 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/users/me",
            "POST /api/v1/users/me/password",
            "POST /api/v1/auth/logout");
    private final SecurityErrorWriter errors;

    /** 注入统一安全错误写入器。 */
    public PasswordChangeRequiredFilter(SecurityErrorWriter errors) {
        this.errors = errors;
    }

    /** 拒绝受限 Token 访问业务接口，避免绕过首次改密要求。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.mustChangePassword()
                && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            errors.write(request, response, 403, "AUTH_PASSWORD_CHANGE_REQUIRED",
                    "必须先修改一次性密码");
            return;
        }
        chain.doFilter(request, response);
    }
}
