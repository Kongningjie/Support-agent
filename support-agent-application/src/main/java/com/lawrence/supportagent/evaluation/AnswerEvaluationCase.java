package com.lawrence.supportagent.evaluation;

import java.util.List;

/**
 * 一条带确定性质量门禁的固定中文回答评测用例。
 *
 * @param caseId 稳定用例 ID
 * @param category 用例类别
 * @param input 交给被测能力的用户输入或工单事实
 * @param evidence 仅允许模型使用的冻结证据
 * @param allowedCitations 允许出现的证据编号
 * @param requiredFacts 答案必须原样包含的事实
 * @param forbiddenFacts 答案不得包含的事实
 * @param exactValues 必须保真的精确值
 * @param expectedRefusal 是否期望无知识克制响应
 * @param schemaType 输出契约类型
 */
public record AnswerEvaluationCase(
        String caseId,
        AnswerEvaluationCategory category,
        String input,
        List<AnswerEvaluationEvidence> evidence,
        List<String> allowedCitations,
        List<String> requiredFacts,
        List<String> forbiddenFacts,
        List<String> exactValues,
        boolean expectedRefusal,
        AnswerEvaluationSchemaType schemaType) { }
