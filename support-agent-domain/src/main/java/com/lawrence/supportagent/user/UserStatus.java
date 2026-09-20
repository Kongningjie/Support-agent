package com.lawrence.supportagent.user;

/** 本地账号是否允许登录和继续使用已签发 Token。 */
public enum UserStatus {
    /** 账号可登录。 */
    ACTIVE,
    /** 账号被管理员禁用。 */
    DISABLED
}
