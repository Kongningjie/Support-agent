package com.lawrence.supportagent.evaluation;

/**
 * 汇总某类问题最高 Rerank 分数的范围，不保存问题或模型正文。
 *
 * @param sampleCount 具有有效 Rerank 分数的样本数量
 * @param minimum 最低的候选最高分
 * @param maximum 最高的候选最高分
 */
public record RetrievalEvaluationScoreRange(int sampleCount, double minimum, double maximum) {
    /** 校验样本数量和零到一的有限分数范围。 */
    public RetrievalEvaluationScoreRange {
        if (sampleCount < 1 || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                || minimum < 0 || maximum > 1 || minimum > maximum) {
            throw new IllegalArgumentException("Rerank 分数范围不合法");
        }
    }
}
