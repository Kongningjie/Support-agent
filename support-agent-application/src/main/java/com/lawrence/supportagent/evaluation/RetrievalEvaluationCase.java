package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;
import java.util.Map;

/**
 * 一条固定、人工标注且只读的检索评测用例。
 *
 * @param caseId 用例稳定唯一标识
 * @param query 发送给检索服务的中文问题
 * @param relevantSourceKeys 人工标注的稳定相关来源键
 * @param expectedStatus 人工标注的预期三态结果
 * @param requiredExactTerms 前五候选必须完整保留的精确技术词
 * @param description 用例目的和人工判断依据
 * @param category 冻结的问题分类
 * @param relevanceGrades 各稳定来源键对应的一至三级相关等级
 * @param shouldBeNoHit 是否必须判定为无可靠知识
 * @param requiresMultiTurnContext 是否依赖前序对话上下文
 * @param hasKnowledgeConflict 是否存在需要披露的知识冲突
 */
public record RetrievalEvaluationCase(String caseId, String query,
                                      List<String> relevantSourceKeys,
                                      RetrievalStatus expectedStatus,
                                      List<String> requiredExactTerms,
                                      String description, String category,
                                      Map<String, Integer> relevanceGrades,
                                      boolean shouldBeNoHit,
                                      boolean requiresMultiTurnContext,
                                      boolean hasKnowledgeConflict) {
    /** 兼容既有测试构造方式，并从稳定用例 ID 推导治理字段。 */
    public RetrievalEvaluationCase(String caseId, String query, List<String> relevantSourceKeys,
                                   RetrievalStatus expectedStatus, List<String> requiredExactTerms,
                                   String description) {
        this(caseId, query, relevantSourceKeys, expectedStatus, requiredExactTerms, description,
                caseId.substring(0, caseId.indexOf('-')),
                relevantSourceKeys.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value, value -> 1)),
                expectedStatus == RetrievalStatus.NO_RELIABLE_KNOWLEDGE,
                caseId.startsWith("MULTITURN-"), caseId.startsWith("CONFLICT-"));
    }
}
