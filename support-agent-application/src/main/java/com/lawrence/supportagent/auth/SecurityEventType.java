package com.lawrence.supportagent.auth;

/** 允许写入安全审计和低基数指标的稳定事件类型。 */
public enum SecurityEventType {
    /** 登录成功。 */ LOGIN_SUCCEEDED,
    /** 登录凭据失败。 */ LOGIN_FAILED,
    /** 登录被临时锁定拒绝。 */ LOGIN_BLOCKED,
    /** 用户修改本人密码。 */ PASSWORD_CHANGED,
    /** 管理员重置一次性密码。 */ PASSWORD_RESET,
    /** 管理员解除账号锁定。 */ ACCOUNT_UNLOCKED,
    /** 用户角色发生变化。 */ ROLE_CHANGED,
    /** 用户状态发生变化。 */ STATUS_CHANGED,
    /** 当前用户全部 Token 被撤销。 */ TOKENS_REVOKED,
    /** 平台管理员创建知识空间。 */ SPACE_CREATED,
    /** 平台管理员修改空间元数据。 */ SPACE_UPDATED,
    /** 平台管理员停用知识空间。 */ SPACE_DISABLED,
    /** 平台管理员重新启用知识空间。 */ SPACE_ENABLED,
    /** 空间成员关系首次建立。 */ SPACE_MEMBER_ADDED,
    /** 活动成员的空间角色发生变化。 */ SPACE_MEMBER_ROLE_CHANGED,
    /** 已撤销成员关系恢复为活动。 */ SPACE_MEMBER_RESTORED,
    /** 活动成员关系被撤销。 */ SPACE_MEMBER_REVOKED
}
