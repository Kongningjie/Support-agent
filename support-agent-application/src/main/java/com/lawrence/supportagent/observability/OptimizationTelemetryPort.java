package com.lawrence.supportagent.observability;

/**
 * 接收二期优化所需的低基数耗时、结果和模型用量指标。
 *
 * <p>实现不得记录问题正文、Prompt、知识正文或模型输出。</p>
 */
public interface OptimizationTelemetryPort {
    /** 记录一个阶段的耗时和是否成功。 */
    void recordDuration(Operation operation, long durationMs, boolean succeeded);

    /** 记录一次模型调用的聚合用量，不携带任何输入或输出正文。 */
    void recordUsage(ModelOperation operation, long inputTokens, long outputTokens,
                     long itemCount, long characterCount);

    /** 返回不执行任何外部副作用的默认实现。 */
    static OptimizationTelemetryPort noOp() {
        return new OptimizationTelemetryPort() {
            /** {@inheritDoc} */
            @Override public void recordDuration(Operation operation, long durationMs,
                                                 boolean succeeded) { }
            /** {@inheritDoc} */
            @Override public void recordUsage(ModelOperation operation, long inputTokens,
                                              long outputTokens, long itemCount,
                                              long characterCount) { }
        };
    }

    /** 固定的耗时指标阶段，禁止把运行 ID 等高基数字段作为标签。 */
    enum Operation {
        /** 完整检索。 */ RETRIEVAL,
        /** 查询或文档 Embedding。 */ EMBEDDING,
        /** 候选重排序。 */ RERANK,
        /** Chat 模型首 Token。 */ MODEL_FIRST_TOKEN,
        /** 请求开始到安全首片段。 */ SAFE_FIRST_DELTA,
        /** 请求开始到答案完成事件。 */ ANSWER_COMPLETE,
        /** 完整聊天请求。 */ CHAT_REQUEST
    }

    /** 固定模型用量分类。 */
    enum ModelOperation {
        /** Chat 生成。 */ CHAT,
        /** 文档或查询向量化。 */ EMBEDDING,
        /** 候选重排序。 */ RERANK
    }
}
