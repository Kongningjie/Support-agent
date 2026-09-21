package com.lawrence.supportagent.security;

/** 定义不可信模型输入在当前调用中的固定处置动作。 */
public enum PromptSecurityAction {
    /** 未发现注入信号，内容仍按不可信数据进行结构化包装。 */
    ALLOW,
    /** 存在模糊或安全讨论信号，允许在增强边界下继续处理。 */
    GUARD,
    /** 存在高置信度攻击组合，禁止内容进入模型调用。 */
    BLOCK
}
