package com.lawrence.supportagent.knowledgespace;

/** 知识空间读取可见性；编辑和管理始终要求显式成员角色。 */
public enum KnowledgeSpaceVisibility {
    /** 所有活动账号均可读取。 */
    ENTERPRISE,
    /** 只有活动成员和平台管理员可读取。 */
    RESTRICTED
}
