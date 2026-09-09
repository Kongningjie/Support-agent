package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.util.List;

/**
 * 保存单条评测用例的预期、实际状态和有序来源。
 *
 * @param caseId 对应固定用例的稳定标识
 * @param expectedStatus 人工标注的预期三态结果
 * @param actualStatus 本次检索产生的实际三态结果
 * @param rankedSourceIds 前十候选按首次出现去重后的有序来源 ID
 * @param exactTermsSatisfied 前五候选是否覆盖全部必需精确词
 * @param failureMessage 单条执行失败的脱敏摘要，正常完成时为空
 */
public record RetrievalEvaluationCaseResult(String caseId, RetrievalStatus expectedStatus,
                                            RetrievalStatus actualStatus,
                                            List<String> rankedSourceIds,
                                            boolean exactTermsSatisfied,
                                            String failureMessage) {
}
