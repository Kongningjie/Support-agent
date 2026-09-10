package com.lawrence.supportagent.evaluation;

/**
 * 保存回答评测所允许使用的一条冻结证据。
 *
 * @param sourceKey 跨运行稳定的来源标识
 * @param sourceType 来源类型
 * @param sourceId 来源内部编号
 * @param title 来源标题
 * @param content 冻结证据正文
 */
public record AnswerEvaluationEvidence(String sourceKey, String sourceType, long sourceId,
                                       String title, String content) { }
