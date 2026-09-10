package com.lawrence.supportagent.evaluation;

import java.util.List;

/**
 * 保存单条模型回答的确定性质量校验结果。
 *
 * @param passed 是否通过全部硬门禁
 * @param failures 未通过的稳定规则编号
 */
public record AnswerQualityAssessment(boolean passed, List<String> failures) { }
