package com.lawrence.supportagent.security;

/** 定义模型完整输出在发送或持久化前的固定处置动作。 */
public enum ModelOutputAction {
    /** 输出通过全部适用规则，可以继续发送或持久化。 */
    PASS,
    /** 输出存在可通过完整重生成修复的问题。 */
    REGENERATE,
    /** 输出存在不可恢复泄漏，当前结果必须直接拒绝。 */
    REJECT
}
