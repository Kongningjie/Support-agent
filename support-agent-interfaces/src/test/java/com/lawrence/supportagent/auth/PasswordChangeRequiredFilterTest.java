package com.lawrence.supportagent.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lawrence.supportagent.user.UserRole;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 验证一次性密码 Token 只能访问冻结的改密流程接口。 */
class PasswordChangeRequiredFilterTest {
    /** 每个测试后清理线程安全上下文。 */
    @AfterEach void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 受限 Token 访问聊天时必须返回稳定 403 且不继续过滤链。 */
    @Test void shouldRejectBusinessEndpoint() throws Exception {
        SecurityErrorWriter errors = mock(SecurityErrorWriter.class);
        FilterChain chain = mock(FilterChain.class);
        authenticate(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PasswordChangeRequiredFilter(errors).doFilter(request, response, chain);

        verify(errors).write(request, response, 403, "AUTH_PASSWORD_CHANGE_REQUIRED",
                "必须先修改一次性密码");
        verify(chain, never()).doFilter(request, response);
    }

    /** 受限 Token 访问本人改密接口时必须继续请求链。 */
    @Test void shouldAllowPasswordChangeEndpoint() throws Exception {
        SecurityErrorWriter errors = mock(SecurityErrorWriter.class);
        FilterChain chain = mock(FilterChain.class);
        authenticate(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/users/me/password");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PasswordChangeRequiredFilter(errors).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(errors, never()).write(request, response, 403,
                "AUTH_PASSWORD_CHANGE_REQUIRED", "必须先修改一次性密码");
    }

    /** 建立带有或不带强制改密标记的认证上下文。 */
    private void authenticate(boolean restricted) {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "alice", UserRole.USER, restricted);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
