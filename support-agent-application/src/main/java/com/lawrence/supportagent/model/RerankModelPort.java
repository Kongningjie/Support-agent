package com.lawrence.supportagent.model;

import java.util.List;

/** 隔离按稳定分块 ID 返回结果的重排模型。 */
public interface RerankModelPort {
    /** 对不超过三十个候选重排，响应必须完整覆盖输入 ID。 */
    List<RerankScore> rerank(String query, List<RerankDocument> documents);

    /** @param chunkId 稳定分块 ID @param content 供模型判断的标题与正文 */
    record RerankDocument(String chunkId, String content) { }

    /** @param chunkId 稳定分块 ID @param score 零到一的相关性分数 */
    record RerankScore(String chunkId, double score) { }
}
