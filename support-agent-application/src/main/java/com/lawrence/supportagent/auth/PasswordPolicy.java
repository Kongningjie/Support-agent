package com.lawrence.supportagent.auth;

import java.nio.charset.StandardCharsets;

/** 执行阶段 11 冻结的 BCrypt 明文密码长度约束。 */
public final class PasswordPolicy {
    /** 禁止实例化无状态规则类。 */
    private PasswordPolicy() { }

    /** 校验密码为 12～72 个字符且 UTF-8 不超过 BCrypt 的 72 字节上限。 */
    public static String validate(String password) {
        if (password == null || password.length() < 12 || password.length() > 72
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码必须为 12 至 72 个字符且 UTF-8 编码不能超过 72 字节");
        }
        return password;
    }
}
