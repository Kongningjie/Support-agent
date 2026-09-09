package com.lawrence.supportagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 一份完成版本和内容哈希锁定的评测数据快照。
 *
 * @param kind 数据集用途
 * @param version 人工维护的不可变数据集版本
 * @param contentSha256 用例与语料原始 UTF-8 内容的 SHA-256
 * @param cases 已完成规范化和一致性校验的用例
 * @param sourceKeysByTypeAndTitle 来源类型与标题到稳定 sourceKey 的映射
 */
public record RetrievalEvaluationDatasetSnapshot(
        EvaluationDatasetKind kind, String version, String contentSha256,
        List<RetrievalEvaluationCase> cases, Map<String, String> sourceKeysByTypeAndTitle) {
}
