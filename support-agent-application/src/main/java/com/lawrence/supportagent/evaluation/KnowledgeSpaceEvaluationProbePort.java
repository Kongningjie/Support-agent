package com.lawrence.supportagent.evaluation;

import java.util.List;
import java.util.Objects;

/** 由受控评测运行器执行单条空间场景并返回不含正文的观测结果。 */
public interface KnowledgeSpaceEvaluationProbePort {
    /** 执行一条固定场景。 */
    Observation evaluate(KnowledgeSpaceEvaluationCase testCase);

    /**
     * 保存单条场景的安全观测值。
     *
     * @param rerankSources 进入 Rerank 的稳定来源键
     * @param citationSources 最终引用的稳定来源键
     * @param answerFactSources 回答事实所依据的稳定来源键
     * @param detailSources 来源详情返回的稳定来源键
     * @param topFiveSources Top5 允许来源键
     * @param returnedExactTerms Top5 中实际保留的精确词
     * @param permissionRejected 权限变化场景是否被拒绝
     * @param noHit 是否判定为无可靠知识
     * @param safeResponse 仅用于防枚举检查的脱敏响应文本
     */
    record Observation(List<String> rerankSources, List<String> citationSources,
                       List<String> answerFactSources, List<String> detailSources,
                       List<String> topFiveSources, List<String> returnedExactTerms,
                       boolean permissionRejected, boolean noHit, String safeResponse) {
        /** 冻结运行器返回的集合并拒绝不完整观测，避免统计期间结果漂移。 */
        public Observation {
            rerankSources = List.copyOf(rerankSources);
            citationSources = List.copyOf(citationSources);
            answerFactSources = List.copyOf(answerFactSources);
            detailSources = List.copyOf(detailSources);
            topFiveSources = List.copyOf(topFiveSources);
            returnedExactTerms = List.copyOf(returnedExactTerms);
            Objects.requireNonNull(safeResponse, "safeResponse must not be null");
        }
    }
}
