package com.lawrence.supportagent.retrieval;

/** 定义单个检索或重排分支的安全观测状态。 */
public enum BranchStatus {
    SUCCEEDED,
    FAILED,
    DEGRADED,
    SKIPPED
}
