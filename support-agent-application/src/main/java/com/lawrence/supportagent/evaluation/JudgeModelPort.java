package com.lawrence.supportagent.evaluation;

import java.util.List;

/** 独立模型评审能力；不接收人工分数或来源模型身份。 */
public interface JudgeModelPort {
    /** 返回本实例实际使用的模型名称；未配置时为空。 */
    String modelName();
    /** 返回评审模板和结构契约版本，便于按版本独立校准。 */
    default String promptVersion() { return JudgeUseCase.PROMPT_VERSION; }
    /** 对脱敏样本评一次；异常由用例分类，不得返回伪造评分。 */
    Result evaluate(HumanEvaluationRepository.Sample sample);
    /** 单维结构化结论，证据位置为 Unicode 代码点半开区间，可同时为空。 */
    record Dimension(int score, String reason, Integer evidenceStart, Integer evidenceEnd) { }
    /** 五维顺序为事实、引用、完整、操作、清晰；Token 缺失时保持为空。 */
    record Result(List<Dimension> dimensions, Long inputTokens, Long outputTokens) {
        /** 防止调用方修改评审结果。 */
        public Result { dimensions = List.copyOf(dimensions); }
    }
}
