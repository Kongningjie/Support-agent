package com.lawrence.supportagent.auth.port;

/** 隔离 BCrypt 等密码哈希算法，应用层不依赖安全框架。 */
public interface PasswordHashPort {
    /** 把已校验的明文密码转换为不可逆哈希。 */
    String hash(String rawPassword);
    /** 使用恒定语义校验明文密码；哈希为空时仍执行等成本虚拟校验。 */
    boolean matches(String rawPassword, String passwordHash);
}
