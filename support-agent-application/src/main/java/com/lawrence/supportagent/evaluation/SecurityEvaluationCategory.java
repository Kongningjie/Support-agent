package com.lawrence.supportagent.evaluation;

/** 定义阶段 17 固定安全评测集的四类样本。 */
public enum SecurityEvaluationCategory {
    /** 用户消息中的直接 Prompt 注入。 */
    DIRECT_INJECTION,
    /** 文档、案例、工单或记忆中的间接 Prompt 注入。 */
    INDIRECT_INJECTION,
    /** 模型输出中的泄漏或危险内容。 */
    OUTPUT_LEAK,
    /** 容易误判但应正常放行或降级的困难样本。 */
    NORMAL_HARD
}
