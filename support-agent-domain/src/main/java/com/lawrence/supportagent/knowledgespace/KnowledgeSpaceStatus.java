package com.lawrence.supportagent.knowledgespace;

/** 知识空间生命周期状态；空间不提供物理删除。 */
public enum KnowledgeSpaceStatus {
    /** 可被授权用户选择、读取和治理。 */
    ACTIVE,
    /** 保留历史审计但禁止新检索和写入。 */
    DISABLED
}
