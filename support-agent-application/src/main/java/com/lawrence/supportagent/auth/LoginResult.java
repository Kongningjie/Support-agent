package com.lawrence.supportagent.auth;

import java.time.Instant;

/** 登录成功后一次性返回给客户端的原始不透明 Token 及用户摘要。 */
public record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserView user) {
    /** 校验登录结果不包含空 Token、过期时间或用户。 */
    public LoginResult {
        if (accessToken == null || accessToken.isBlank() || !"Bearer".equals(tokenType)
                || expiresAt == null || user == null) {
            throw new IllegalArgumentException("登录结果字段不能为空");
        }
    }
}
