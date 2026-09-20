package com.lawrence.supportagent.user;

/** 本地账号的两级角色，不承载组织或多租户语义。 */
public enum UserRole {
    /** 只能访问本人会话和工单的普通用户。 */
    USER,
    /** 可管理用户和知识，并可访问全部工单的管理员。 */
    ADMIN
}
