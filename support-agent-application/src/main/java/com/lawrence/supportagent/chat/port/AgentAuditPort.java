package com.lawrence.supportagent.chat.port;

import com.lawrence.supportagent.chat.IntentDecision;
import com.lawrence.supportagent.retrieval.RetrievalResult;
import java.time.Instant;
import java.util.UUID;

/** 隔离 Agent 运行和检索轨迹的 MySQL 安全摘要持久化。 */
public interface AgentAuditPort {
    /** 在取得会话运行权后创建 RUNNING 摘要。 */
    void start(UUID runId, UUID conversationId, UUID clientMessageId, Instant now);

    /** 更新已确定的意图摘要。 */
    void recordIntent(UUID runId, IntentDecision decision);

    /** 写入不包含查询和正文的检索轨迹摘要。 */
    void recordRetrieval(UUID runId, String standaloneQuery, RetrievalResult result,
                         double groundedThreshold, Instant now);

    /** 将运行标记为成功并保存模型与 Prompt 版本摘要。 */
    void succeed(UUID runId, String promptVersion, String chatModel,
                 String embeddingModel, String rerankModel, long durationMs, Instant now);

    /** 将运行标记为失败或中断，不保存原始模型内容。 */
    void fail(UUID runId, String status, String errorCode, long durationMs, Instant now);
}
