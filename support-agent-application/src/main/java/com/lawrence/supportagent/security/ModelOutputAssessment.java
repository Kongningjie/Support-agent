package com.lawrence.supportagent.security;

import java.util.List;
import java.util.Objects;

/** 保存一次完整模型输出安全评估的动作与低基数规则编号。 */
public record ModelOutputAssessment(ModelOutputAction action,
                                    List<ModelOutputRule> rules) {
    /** 拒绝空动作并稳定复制规则列表。 */
    public ModelOutputAssessment {
        action = Objects.requireNonNull(action, "模型输出安全动作不能为空");
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 返回适合安全反馈给重生成模型的规则名称。 */
    public List<String> feedbackRules() {
        return rules.stream().map(Enum::name).toList();
    }
}
