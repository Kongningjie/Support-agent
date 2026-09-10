package com.lawrence.supportagent.retrieval;

/** 说明评测检索为何接受、拒绝候选或判定技术故障。 */
public enum RetrievalDecisionReason {
    /** 当前模式所需检索分支全部失败。 */
    RETRIEVAL_BRANCH_FAILED,
    /** 来源有效性回查后没有任何候选。 */
    NO_CANDIDATE,
    /** Rerank 成功，但所有候选都低于可靠知识门槛。 */
    BELOW_GROUNDED_THRESHOLD,
    /** Rerank 不可用或当前模式不执行 Rerank，且候选不满足保守证据信号。 */
    INSUFFICIENT_DEGRADED_SIGNAL,
    /** 至少一个候选满足当前可靠知识规则。 */
    RELIABLE_EVIDENCE_PRESENT
}
