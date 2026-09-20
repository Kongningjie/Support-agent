package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从 Authorization Header 解析不透明 Bearer Token 并建立只读认证上下文。 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String PREFIX = "Bearer ";
    private final AccessTokenPort tokens;

    /** 注入 Token 认证端口。 */
    public BearerTokenAuthenticationFilter(AccessTokenPort tokens) {
        this.tokens = tokens;
    }

    /** 对合法 Token 建立 Spring Security 认证；无效 Token 保持匿名交给统一入口处理。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX) && header.length() > PREFIX.length()) {
            String rawToken = header.substring(PREFIX.length()).strip();
            tokens.authenticate(rawToken).ifPresent(user -> {
                var authority = new SimpleGrantedAuthority("ROLE_" + user.role().name());
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
