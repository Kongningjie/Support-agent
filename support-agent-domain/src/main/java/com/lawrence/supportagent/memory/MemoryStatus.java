package com.lawrence.supportagent.memory;

/** 长期记忆从模型候选到用户确认或撤销的生命周期状态。 */
public enum MemoryStatus {
    /** 模型提出但尚未获得用户确认，不允许注入模型上下文。 */
    PROPOSED,
    /** 用户已经确认且尚未过期，允许在预算内注入上下文。 */
    ACTIVE,
    /** 用户已经撤销，不再注入上下文但仍可供本人查看。 */
    REVOKED
}
