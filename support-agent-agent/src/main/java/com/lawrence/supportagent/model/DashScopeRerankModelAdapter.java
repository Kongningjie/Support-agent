package com.lawrence.supportagent.model;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import java.util.ArrayList;
import java.util.List;

/** 使用 DashScope 官方 SDK 实现候选分块重排。 */
public class DashScopeRerankModelAdapter implements RerankModelPort {
    private final String apiKey;
    private final String modelName;
    private final TextReRank client;

    /** 使用显式 Base URL 构造 SDK 客户端，避免环境地址未传入适配器。 */
    public DashScopeRerankModelAdapter(String apiKey, String modelName, String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = new TextReRank("http", baseUrl);
    }

    /** {@inheritDoc} */
    @Override
    public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
        if (apiKey == null || apiKey.isBlank()) throw new ModelInvocationException(
                "DASHSCOPE_NOT_CONFIGURED", "当前环境未配置 DashScope 密钥", false, null);
        try {
            TextReRankParam parameter = TextReRankParam.builder().apiKey(apiKey).model(modelName)
                    .query(query).documents(documents.stream().map(RerankDocument::content).toList())
                    .topN(documents.size()).returnDocuments(false).build();
            TextReRankResult result = client.call(parameter);
            if (result == null || result.getStatusCode() == null || result.getStatusCode() / 100 != 2
                    || result.getOutput() == null || result.getOutput().getResults() == null) {
                throw new ModelInvocationException("RERANK_PROVIDER_FAILURE", "Rerank 服务暂时不可用", true, null);
            }
            List<RerankScore> scores = new ArrayList<>();
            for (TextReRankOutput.Result item : result.getOutput().getResults()) {
                if (item.getIndex() == null || item.getIndex() < 0 || item.getIndex() >= documents.size()
                        || item.getRelevanceScore() == null) {
                    throw new ModelInvocationException("RERANK_RESULT_INVALID", "Rerank 返回结构无效", false, null);
                }
                scores.add(new RerankScore(documents.get(item.getIndex()).chunkId(), item.getRelevanceScore()));
            }
            return List.copyOf(scores);
        } catch (ModelInvocationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelInvocationException("RERANK_PROVIDER_FAILURE", "Rerank 服务暂时不可用", true, exception);
        }
    }
}
