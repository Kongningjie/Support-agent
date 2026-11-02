package com.lawrence.supportagent.observability;

/**
 * 接收二期优化所需的低基数耗时、结果和模型用量指标。
 *
 * <p>实现不得记录问题正文、Prompt、知识正文或模型输出。</p>
 */
public interface OptimizationTelemetryPort {
    /** 记录请求级取消收尾；只允许固定原因/阶段/结果，不能把未知视为清理成功。 */
    default void recordChatCancellation(com.lawrence.supportagent.chat.ChatExecutionControl.CancellationReason reason,
                                         ChatStage stage, CleanupResult result, long durationMs, boolean cancelledExecution) { }

    /** 记录当前请求的上游取消技术动作，不等同供应商停止计算；次数为零不记录。 */
    default void recordUpstreamCancellation(UpstreamResult result, int count) { }

    /** 固定业务阶段，仅作低基数观测，不写入业务状态。 */
    enum ChatStage {
        /** 尚未进入业务。 */ PREPARING,
        /** 上下文组装。 */ CONTEXT,
        /** 意图识别。 */ INTENT,
        /** 检索及证据检查。 */ RETRIEVAL,
        /** 模型生成。 */ GENERATION,
        /** 完整输出检查。 */ VALIDATION,
        /** 安全正文发送。 */ SENDING,
        /** 最终提交及核实。 */ COMMIT,
        /** 可选成功后处理。 */ POST_PROCESSING,
        /** 已完成结果重放。 */ REPLAY
    }

    /** 取消后收尾的技术结果；不新增数据库状态。 */
    enum CleanupResult {
        /** 围栏释放与失败审计调用返回。 */ RELEASED,
        /** 至少一项清理抛异常。 */ PARTIAL_FAILURE,
        /** 已明确提交成功，不回滚。 */ COMMITTED,
        /** 结果不可确认。 */ UNKNOWN,
        /** 不再拥有原运行，未修改新运行。 */ NOT_OWNED,
        /** 仅停止重放发送。 */ REPLAY_STOPPED
    }

    /** 上游取消技术动作；LOCAL_RELEASED 只表示本地取消操作返回。 */
    enum UpstreamResult {
        /** 已通知活动订阅取消。 */ REQUESTED,
        /** 本地订阅/请求取消操作返回或没有活动资源。 */ LOCAL_RELEASED,
        /** 本地取消动作抛异常。 */ RELEASE_FAILED
    }
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
        /** 完整聊天请求。 */ CHAT_REQUEST,
        /** 单会话结构化滚动摘要。 */ CONVERSATION_SUMMARY
    }

    /** 固定模型用量分类。 */
    enum ModelOperation {
        /** Chat 生成。 */ CHAT,
        /** 文档或查询向量化。 */ EMBEDDING,
        /** 候选重排序。 */ RERANK,
        /** 单会话结构化滚动摘要。 */ SUMMARY
    }
}
