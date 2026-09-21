package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AuthenticationPort;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/** 配置无 Session 的不透明 Bearer Token 认证与两级角色边界。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    /** 创建统一安全错误写入器。 */
    @Bean
    public SecurityErrorWriter securityErrorWriter(ObjectMapper mapper, TimeProvider time) {
        return new SecurityErrorWriter(mapper, time);
    }

    /** 创建 Bearer Token 认证过滤器。 */
    @Bean
    public BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(AuthenticationPort authentication) {
        return new BearerTokenAuthenticationFilter(authentication);
    }

    /** 创建强制改密 Token 的接口限制过滤器。 */
    @Bean
    public PasswordChangeRequiredFilter passwordChangeRequiredFilter(SecurityErrorWriter errors) {
        return new PasswordChangeRequiredFilter(errors);
    }

    /** 定义匿名、管理员和普通认证请求的 URL 规则。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    BearerTokenAuthenticationFilter bearer,
                                                    PasswordChangeRequiredFilter passwordChange,
                                                    SecurityErrorWriter errors) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/v1/auth/login", "/actuator/health/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/async-tasks/**",
                                "/api/v1/knowledge/**", "/api/v1/resolved-cases/**",
                                "/api/v1/retrieval-evaluations/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.write(
                                request, response, 401, "AUTH_UNAUTHORIZED", "认证信息无效或已经过期"))
                        .accessDeniedHandler((request, response, exception) -> errors.write(
                                request, response, 403, "AUTH_FORBIDDEN", "当前用户没有执行此操作的权限")))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(passwordChange, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
