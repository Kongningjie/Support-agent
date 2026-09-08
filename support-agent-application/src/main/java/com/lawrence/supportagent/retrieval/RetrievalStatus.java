package com.lawrence.supportagent.retrieval;

/** 定义检索完成后的三种互斥业务状态。 */
public enum RetrievalStatus {
    GROUNDED,
    NO_RELIABLE_KNOWLEDGE,
    RETRIEVAL_FAILED
}
