package com.lawrence.supportagent.evaluation;

/**
 * 保存模型选择结论及其可审计依据。
 *
 * @param status 自动选择、需要人工决定或没有合格候选
 * @param modelName 自动选择或建议人工比较的模型名称
 * @param reason 不含 Prompt 与模型正文的选择依据
 */
public record AnswerModelSelection(Status status, String modelName, String reason) {
    /** 模型选择的三种终态。 */
    public enum Status {
        /** 已按冻结规则自动选出。 */ SELECTED,
        /** 高成本模型存在独有质量收益，需要用户决定。 */ USER_DECISION_REQUIRED,
        /** 没有候选通过全部质量硬门禁。 */ NO_QUALIFIED_CANDIDATE
    }
}
