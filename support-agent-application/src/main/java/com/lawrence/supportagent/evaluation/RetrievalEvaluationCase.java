package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;

/**
 * 一条固定、人工标注且只读的检索评测用例。
 *
 * @param caseId 用例稳定唯一标识
 * @param query 发送给检索服务的中文问题
 * @param relevantSourceIds 人工标注的相关来源类型与 ID
 * @param expectedStatus 人工标注的预期三态结果
 * @param requiredExactTerms 前五候选必须完整保留的精确技术词
 * @param description 用例目的和人工判断依据
 */
public record RetrievalEvaluationCase(String caseId, String query,
                                      List<String> relevantSourceIds,
                                      RetrievalStatus expectedStatus,
                                      List<String> requiredExactTerms,
                                      String description) {
}
