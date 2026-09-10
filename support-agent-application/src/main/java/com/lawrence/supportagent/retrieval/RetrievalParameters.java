package com.lawrence.supportagent.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存一次检索运行使用的完整参数快照，防止生产逻辑和离线评测各自维护常量。
 *
 * @param bm25TopK BM25 分支最多返回的候选数量
 * @param vectorTopK 向量分支最多返回的候选数量
 * @param vectorCandidates Elasticsearch 近似向量搜索检查的候选池大小
 * @param vectorMinimumSimilarity 向量候选允许的最低余弦相似度
 * @param rrfK RRF 公式中用于平滑名次差异的常数
 * @param fusionTopK RRF 融合后保留的候选数量
 * @param rerankTopK 送入 Rerank 模型的候选数量
 * @param rankedTopK 离线评测保留的原始排名数量
 * @param finalTopK 最终允许进入回答证据的候选数量
 * @param groundedThreshold Rerank 成功时判定可靠知识的最低分数
 * @param analysisProfile Elasticsearch 文本字段使用的冻结分析配置
 * @param titleWeight BM25 查询中来源标题的有限权重
 * @param headingWeight BM25 查询中标题路径的有限权重
 * @param contentWeight BM25 查询中分块正文的有限权重
 * @param exactTermWeight BM25 查询中精确技术词完全匹配的有限权重
 */
public record RetrievalParameters(int bm25TopK, int vectorTopK, int vectorCandidates,
                                  double vectorMinimumSimilarity, int rrfK,
                                  int fusionTopK, int rerankTopK, int rankedTopK,
                                  int finalTopK, double groundedThreshold,
                                  RetrievalAnalysisProfile analysisProfile,
                                  double titleWeight, double headingWeight,
                                  double contentWeight, double exactTermWeight) {

    /** 校验数量关系、分数范围以及一期最多五条证据的公共契约。 */
    public RetrievalParameters {
        if (bm25TopK < 1 || bm25TopK > 1_000
                || vectorTopK < 1 || vectorTopK > 1_000
                || vectorCandidates < vectorTopK || vectorCandidates > 10_000
                || !Double.isFinite(vectorMinimumSimilarity)
                || vectorMinimumSimilarity < -1 || vectorMinimumSimilarity > 1
                || rrfK < 1 || rrfK > 10_000 || fusionTopK < 1 || fusionTopK > 1_000
                || fusionTopK > bm25TopK + vectorTopK
                || rerankTopK < 1 || rerankTopK > fusionTopK
                || rankedTopK < 1 || rankedTopK > rerankTopK
                || finalTopK < 1 || finalTopK > 5 || finalTopK > rerankTopK
                || !Double.isFinite(groundedThreshold)
                || groundedThreshold < 0 || groundedThreshold > 1
                || analysisProfile == null || !validWeight(titleWeight)
                || !validWeight(headingWeight) || !validWeight(contentWeight)
                || !validWeight(exactTermWeight)) {
            throw new IllegalArgumentException("检索参数超出允许范围或数量关系不合法");
        }
    }

    /** 返回阶段七冻结生产参数，供适配器和未覆盖配置保持一致行为。 */
    public static RetrievalParameters baseline() {
        return new RetrievalParameters(30, 30, 100, 0.20, 20,
                20, 20, 10, 5, 0.30, RetrievalAnalysisProfile.ICU_ONLY,
                3.0, 2.0, 1.0, 5.0);
    }

    /** 返回阶段六实测基线，仅供阶段六复现和阶段七候选对照。 */
    public static RetrievalParameters stageSixBaseline() {
        return new RetrievalParameters(50, 50, 200, 0.20, 60,
                30, 30, 10, 5, 0.35, RetrievalAnalysisProfile.ICU_ONLY,
                3.0, 2.0, 1.0, 5.0);
    }

    /** 返回适合写入评测报告且顺序稳定的字符串参数快照。 */
    public Map<String, String> asReportMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bm25TopK", Integer.toString(bm25TopK));
        values.put("vectorTopK", Integer.toString(vectorTopK));
        values.put("vectorCandidates", Integer.toString(vectorCandidates));
        values.put("vectorMinimumSimilarity", Double.toString(vectorMinimumSimilarity));
        values.put("rrfK", Integer.toString(rrfK));
        values.put("fusionTopK", Integer.toString(fusionTopK));
        values.put("rerankTopK", Integer.toString(rerankTopK));
        values.put("rankedTopK", Integer.toString(rankedTopK));
        values.put("finalTopK", Integer.toString(finalTopK));
        values.put("rerankGroundedThreshold", Double.toString(groundedThreshold));
        values.put("analysisProfile", analysisProfile.name());
        values.put("titleWeight", Double.toString(titleWeight));
        values.put("headingWeight", Double.toString(headingWeight));
        values.put("contentWeight", Double.toString(contentWeight));
        values.put("exactTermWeight", Double.toString(exactTermWeight));
        return Collections.unmodifiableMap(values);
    }

    /** 创建只改变文本分析方案的参数快照。 */
    public RetrievalParameters withAnalysisProfile(RetrievalAnalysisProfile profile) {
        return new RetrievalParameters(bm25TopK, vectorTopK, vectorCandidates,
                vectorMinimumSimilarity, rrfK, fusionTopK, rerankTopK, rankedTopK,
                finalTopK, groundedThreshold, profile, titleWeight, headingWeight,
                contentWeight, exactTermWeight);
    }

    /** 创建只改变召回宽度变量组的参数快照。 */
    public RetrievalParameters withRecallBreadth(int bm25, int vector, int candidates) {
        return new RetrievalParameters(bm25, vector, candidates, vectorMinimumSimilarity,
                rrfK, fusionTopK, rerankTopK, rankedTopK, finalTopK, groundedThreshold,
                analysisProfile, titleWeight, headingWeight, contentWeight, exactTermWeight);
    }

    /** 创建只改变融合变量组的参数快照。 */
    public RetrievalParameters withFusion(int reciprocalRankK, int fusion, int rerank) {
        return new RetrievalParameters(bm25TopK, vectorTopK, vectorCandidates,
                vectorMinimumSimilarity, reciprocalRankK, fusion, rerank, rankedTopK,
                finalTopK, groundedThreshold, analysisProfile, titleWeight, headingWeight,
                contentWeight, exactTermWeight);
    }

    /** 创建只改变 BM25 字段权重变量组的参数快照。 */
    public RetrievalParameters withFieldWeights(double title, double heading, double content,
                                                double exactTerm) {
        return new RetrievalParameters(bm25TopK, vectorTopK, vectorCandidates,
                vectorMinimumSimilarity, rrfK, fusionTopK, rerankTopK, rankedTopK,
                finalTopK, groundedThreshold, analysisProfile, title, heading, content, exactTerm);
    }

    /** 创建只改变单一全局可靠知识阈值的参数快照。 */
    public RetrievalParameters withGroundedThreshold(double threshold) {
        return new RetrievalParameters(bm25TopK, vectorTopK, vectorCandidates,
                vectorMinimumSimilarity, rrfK, fusionTopK, rerankTopK, rankedTopK,
                finalTopK, threshold, analysisProfile, titleWeight, headingWeight,
                contentWeight, exactTermWeight);
    }

    /** 判断一个字段权重是否为有限且不会形成极端放大的正数。 */
    private static boolean validWeight(double value) {
        return Double.isFinite(value) && value > 0 && value <= 20;
    }
}
