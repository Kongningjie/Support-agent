package com.lawrence.supportagent.security;

/** 在模型完整输出发送或持久化前执行统一、确定性的安全评估。 */
public interface ModelOutputSecurityPolicy {
    /**
     * 评估完整输出，但不得记录或返回输出正文。
     *
     * @param request 输出和最小授权上下文
     * @return 固定动作和低基数规则编号
     */
    ModelOutputAssessment assess(ModelOutputRequest request);
}
