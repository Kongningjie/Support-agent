package com.lawrence.supportagent.feedback;

/** 无帮助反馈使用的稳定原因分类，不表示事实已经人工核实。 */
public enum FeedbackReason {
    /** 用户认为回答不正确。 */
    INCORRECT,
    /** 用户认为回答缺乏充分依据。 */
    UNSUPPORTED,
    /** 用户认为回答不完整。 */
    INCOMPLETE,
    /** 用户认为回答难以理解。 */
    UNCLEAR,
    /** 其他预定义分类无法描述的问题。 */
    OTHER
}
