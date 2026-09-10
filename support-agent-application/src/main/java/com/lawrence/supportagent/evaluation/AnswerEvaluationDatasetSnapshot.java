package com.lawrence.supportagent.evaluation;

import java.util.List;

/**
 * 一份经过版本、内容哈希和类别分布校验的回答评测数据。
 *
 * @param version 人工维护的数据集版本
 * @param contentSha256 用例与引用语料的 SHA-256
 * @param cases 固定顺序的三十条评测用例
 */
public record AnswerEvaluationDatasetSnapshot(String version, String contentSha256,
                                              List<AnswerEvaluationCase> cases) { }
