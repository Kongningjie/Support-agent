package com.lawrence.supportagent.knowledgespace;

/** 空间成员关系状态；撤销记录继续保留审计事实。 */
public enum SpaceMembershipStatus {
    /** 当前成员关系有效。 */
    ACTIVE,
    /** 当前成员关系已撤销。 */
    REVOKED
}
