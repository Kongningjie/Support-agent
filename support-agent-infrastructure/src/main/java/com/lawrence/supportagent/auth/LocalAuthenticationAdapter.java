package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.AuthenticationPort;
import java.util.Optional;

/** 以现有 Redis 不透明 Token 实现本地认证端口。 */
public class LocalAuthenticationAdapter implements AuthenticationPort {
    private final AccessTokenPort tokens;

    /** 注入不透明 Token 端口。 */
    public LocalAuthenticationAdapter(AccessTokenPort tokens) {
        this.tokens = tokens;
    }

    /** {@inheritDoc} */
    @Override public Optional<AuthenticatedUser> authenticate(String credential) {
        return tokens.authenticate(credential);
    }
}
