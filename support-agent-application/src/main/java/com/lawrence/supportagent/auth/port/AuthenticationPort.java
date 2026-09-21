package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import java.util.Optional;

/** 隔离 HTTP 认证与具体本地 Token 或未来外部身份提供商。 */
public interface AuthenticationPort {
    /** 验证调用方凭据并返回稳定的内部认证主体。 */
    Optional<AuthenticatedUser> authenticate(String credential);
}
