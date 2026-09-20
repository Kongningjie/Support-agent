package com.lawrence.supportagent.chat;

/** 表示会话当前是否存在尚未过期的活动运行。 */
public enum ConversationLifecycleStatus {
    /** 会话当前可以接受新的聊天、重置或删除操作。 */
    IDLE,
    /** 会话当前存在有效运行租约，禁止重置或删除。 */
    RUNNING
}
