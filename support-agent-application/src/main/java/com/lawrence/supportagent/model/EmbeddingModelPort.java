package com.lawrence.supportagent.model;

import java.util.List;

/** 隔离文档和查询向量生成能力，不暴露 DashScope SDK 类型。 */
public interface EmbeddingModelPort {
    /** 按输入顺序批量生成文档向量，每个向量必须为 1024 维。 */
    List<List<Double>> embedDocuments(List<String> documents, Runnable batchHeartbeat);

    /** 为后续检索生成单条查询向量。 */
    List<Double> embedQuery(String query);
}
