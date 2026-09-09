package com.lawrence.supportagent.evaluation;

/**
 * 固定检索评测的五项汇总指标，取值均为 0～1。
 *
 * @param recallAt5 前五结果覆盖人工相关来源的平均比例
 * @param mrrAt10 前十首个相关来源倒数排名的平均值
 * @param ndcgAt5 前五结果二元相关性的平均归一化折损累计增益
 * @param noHitAccuracy 无知识用例被正确判断为无可靠知识的比例
 * @param exactTermRecall 精确词用例在前五结果中完整保留要求词的比例
 */
public record RetrievalEvaluationMetrics(double recallAt5, double mrrAt10,
                                         double ndcgAt5, double noHitAccuracy,
                                         double exactTermRecall) {
}
