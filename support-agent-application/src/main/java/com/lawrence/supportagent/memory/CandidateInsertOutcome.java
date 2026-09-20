package com.lawrence.supportagent.memory;

/** 表示长期记忆候选在持久化边界上的固定低基数结果。 */
public enum CandidateInsertOutcome {
    /** 候选已经写入。 */
    INSERTED,
    /** 同用户、同类型和同正文哈希的候选已经存在。 */
    DUPLICATE,
    /** 用户未启用长期记忆，候选未写入。 */
    DISABLED,
    /** 用户保留的记忆已经达到容量上限。 */
    LIMIT_REACHED
}
