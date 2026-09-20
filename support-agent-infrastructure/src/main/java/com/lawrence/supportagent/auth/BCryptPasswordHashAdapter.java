package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.PasswordHashPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 使用 BCrypt strength 12 实现本地密码哈希端口。 */
public class BCryptPasswordHashAdapter implements PasswordHashPort {
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    /** 创建固定 strength 12 的 BCrypt 编码器。 */
    public BCryptPasswordHashAdapter() {
        this.encoder = new BCryptPasswordEncoder(12);
        this.dummyHash = encoder.encode("support-agent-nonexistent-user");
    }

    /** {@inheritDoc} */
    @Override public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** {@inheritDoc} */
    @Override public boolean matches(String rawPassword, String passwordHash) {
        try {
            return encoder.matches(rawPassword, passwordHash == null ? dummyHash : passwordHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
