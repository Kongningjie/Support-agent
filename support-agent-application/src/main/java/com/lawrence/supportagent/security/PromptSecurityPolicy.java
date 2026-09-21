package com.lawrence.supportagent.security;

/** 在模型调用前对各类不可信文本执行统一、确定性的注入风险评估。 */
public interface PromptSecurityPolicy {
    /**
     * 评估一段不可信文本，但不得记录或返回命中正文。
     *
     * @param content 待检查文本，可为空
     * @param source 文本进入模型上下文前的来源
     * @return 固定动作、来源和低基数信号
     */
    PromptSecurityAssessment assess(String content, PromptSecuritySource source);
}
