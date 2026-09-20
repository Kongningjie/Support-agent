package com.lawrence.supportagent.memory;

/** 用户明确确认后可以跨会话复用的三类长期记忆。 */
public enum MemoryType {
    /** 用户对回答方式或交互方式的稳定偏好。 */
    PREFERENCE,
    /** 用户明确要求系统持续遵守的技术、业务或安全约束。 */
    CONSTRAINT,
    /** 用户明确说明且会影响技术建议的运行环境事实。 */
    ENVIRONMENT
}
