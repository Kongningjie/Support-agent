package com.lawrence.supportagent.evaluation;

import java.util.Map;

/**
 * 保存使评测报告可复现但不包含敏感正文的运行上下文。
 *
 * @param reportSchemaVersion 报告 JSON Schema 语义版本
 * @param datasetKind 数据集用途
 * @param datasetVersion 数据集版本
 * @param datasetSha256 数据集内容哈希
 * @param gitCommit 运行代码对应的 Git 提交；有未提交修改时仍记录当前 HEAD
 * @param modelNames Chat、Embedding 和 Rerank 的配置模型名
 * @param retrievalParameters 不会被本次运行修改的检索参数快照
 */
public record RetrievalEvaluationContext(
        String reportSchemaVersion, EvaluationDatasetKind datasetKind,
        String datasetVersion, String datasetSha256, String gitCommit,
        Map<String, String> modelNames, Map<String, String> retrievalParameters) {
}
