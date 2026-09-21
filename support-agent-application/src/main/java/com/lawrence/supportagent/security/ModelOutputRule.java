package com.lawrence.supportagent.security;

/** 定义可安全反馈给模型且不携带正文的低基数输出规则编号。 */
public enum ModelOutputRule {
    /** 输出为空或超过分支长度限制。 */
    OUTPUT_LENGTH_INVALID,
    /** 输出泄漏了本次调用随机标记。 */
    CANARY_LEAK,
    /** 输出包含系统 Prompt 的关键固定片段。 */
    SYSTEM_PROMPT_LEAK,
    /** 输出伪造 system、developer 或 tool 消息边界。 */
    ROLE_MARKER,
    /** 输出包含私钥头。 */
    PRIVATE_KEY,
    /** 输出包含访问 Token、JWT 或 Bearer 凭据。 */
    TOKEN,
    /** 输出包含密码赋值或密码正文。 */
    PASSWORD,
    /** 输出包含 API Key 或访问密钥。 */
    API_KEY,
    /** 输出包含带凭据的数据库连接地址。 */
    DATABASE_CREDENTIAL,
    /** 输出包含 javascript 或 data 等不安全链接协议。 */
    UNSAFE_LINK,
    /** 输出包含中国大陆居民身份证号形态。 */
    ID_CARD,
    /** 输出包含中国大陆手机号形态。 */
    MOBILE_PHONE,
    /** 输出包含当前输入或正确引用证据未授权的邮箱。 */
    EMAIL_NOT_ALLOWED,
    /** RAG 输出引用了不存在的证据编号。 */
    UNKNOWN_CITATION,
    /** RAG 输出缺少必要引用。 */
    CITATION_REQUIRED,
    /** RAG 输出中的精确技术值没有被当前句引用证据支持。 */
    EXACT_VALUE_NOT_SUPPORTED,
    /** 输出命中既有敏感内容策略或包含危险控制字符。 */
    SENSITIVE_CONTENT
}
