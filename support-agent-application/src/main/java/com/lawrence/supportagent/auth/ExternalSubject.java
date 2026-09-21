package com.lawrence.supportagent.auth;

/**
 * 未来外部身份提供商的稳定主体，不包含访问令牌或供应商原始声明。
 * @param issuer 受信任签发方稳定标识
 * @param subject 签发方内不可变主体标识
 */
public record ExternalSubject(String issuer, String subject) {
    /** 拒绝空签发方或主体，避免不完整映射。 */
    public ExternalSubject {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("外部签发方和主体不能为空");
        }
    }
}
