package com.lawrence.supportagent.evaluation;

/** 统一描述输入策略与输出策略的预期评测动作。 */
public enum SecurityEvaluationAction {
    /** 输入可直接使用。 */ ALLOW,
    /** 输入保留但必须作为不可信数据隔离。 */ GUARD,
    /** 输入或上下文必须阻断。 */ BLOCK,
    /** 输出通过全部校验。 */ PASS,
    /** 输出允许执行一次完整重生成。 */ REGENERATE,
    /** 输出不得发送或持久化。 */ REJECT
}
