package com.lawrence.supportagent.security;

/** 定义可安全记录且不携带原始正文的低基数 Prompt 风险信号。 */
public enum PromptSecuritySignal {
    /** 内容要求忽略、替换或降低上级规则。 */
    INSTRUCTION_OVERRIDE,
    /** 内容索取系统 Prompt、隐藏规则或内部状态。 */
    PROMPT_EXFILTRATION,
    /** 内容伪造 system、developer 或 tool 等角色边界。 */
    ROLE_IMPERSONATION,
    /** 内容要求扩大、重复或绕过既定工具权限。 */
    TOOL_ESCALATION,
    /** 内容要求解码并执行隐藏指令。 */
    ENCODED_INSTRUCTION,
    /** 内容在解释、研究或防御语境中讨论安全攻击。 */
    SECURITY_DISCUSSION
}
