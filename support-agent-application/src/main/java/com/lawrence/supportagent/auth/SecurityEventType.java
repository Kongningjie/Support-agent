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
    /** 当前用户全部 Token 被撤销。 */ TOKENS_REVOKED
}
