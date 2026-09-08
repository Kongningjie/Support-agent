package com.lawrence.supportagent.chat.port;

import com.lawrence.supportagent.chat.ChatIntent;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 隔离 Redis 会话、运行租约、消息幂等和工单建议的原子操作。 */
public interface ConversationStorePort {
    /** 原子创建或取得会话执行权，并识别可安全重放的已完成消息。 */
    BeginResult begin(UUID conversationId, UUID clientMessageId, String message,
                      Long expectedVersion, UUID runId, Instant now);

    /** 在运行围栏仍有效时续租。 */
    boolean renew(UUID conversationId, UUID runId, Instant now);

    /** 为当前会话原子分配下一个业务事件序号。 */
    long nextSequence(UUID conversationId, UUID runId);

    /** 为已完成消息的重放原子分配新序号。 */
    long nextReplaySequence(UUID conversationId);

    /** 在运行围栏有效时原子提交完整成功轮次并递增会话版本。 */
    CompletedTurn complete(UUID conversationId, UUID runId, CompletedTurn turn,
                           String serializedAgentState, Instant now);

    /** 释放仍属于指定运行的租约，不保存半轮或递增版本。 */
    void fail(UUID conversationId, UUID runId, Instant now);

    /** 原子取得建议消费租约或返回已创建工单编号。 */
    SuggestionClaim claimSuggestion(UUID conversationId, UUID suggestionId, Instant now);

    /** 把建议标记为已消费并保存首次工单编号。 */
    void consumeSuggestion(UUID conversationId, UUID suggestionId, UUID claimId,
                           String ticketNo, Instant now);

    /** 返回最多六轮且总字符数受限的模型上下文。 */
    List<String> recentContext(UUID conversationId, int maximumTurns, int maximumCharacters);

    /** 表示会话开始结果类型。 */
    enum BeginStatus { ACQUIRED, REPLAY }

    /**
     * @param status 是否取得执行权或重放
     * @param conversationId 会话 UUID
     * @param version 当前会话版本
     * @param replayTurn 重放轮次，非重放时为空
     * @param interruptedRunId 被过期租约接管的旧运行，可为空
     */
    record BeginResult(BeginStatus status, UUID conversationId, long version,
                       CompletedTurn replayTurn, UUID interruptedRunId) { }

    /**
     * @param turnId 成功轮次 UUID
     * @param clientMessageId 用户消息幂等 UUID
     * @param runId 原 Agent 运行 UUID
     * @param userMessage 用户原始输入
     * @param standaloneQuery 独立检索问题
     * @param intent 最终意图
     * @param answer 完整安全答案
     * @param retrievalStatus 检索三态，可为空
     * @param citations 最终引用
     * @param suggestionId 工单建议 ID，可为空
     * @param resultStatus SSE 稳定结果状态
     * @param completedAt 完成 UTC 时间
     * @param conversationVersion 成功后的会话版本
     */
    record CompletedTurn(UUID turnId, UUID clientMessageId, UUID runId, String userMessage,
                         String standaloneQuery, ChatIntent intent, String answer,
                         RetrievalStatus retrievalStatus, List<Citation> citations,
                         UUID suggestionId, String suggestionContext, String resultStatus, Instant completedAt,
                         long conversationVersion) { }

    /**
     * @param citationId 临时引用标识
     * @param documentId 来源 ID 字符串
     * @param documentTitle 来源标题
     * @param headingPath 标题路径
     * @param sourceType 来源类型
     * @param sourceCaseId 案例 ID，仅案例来源有值
     */
    record Citation(String citationId, String documentId, String documentTitle,
                    String headingPath, String sourceType, String sourceCaseId) { }

    /** @param status 消费状态 @param claimId 消费租约 UUID @param frozenContext 冻结上下文 @param sourceTurnId 来源轮次 @param ticketNo 已创建工单号 */
    record SuggestionClaim(String status, UUID claimId, String frozenContext,
                           UUID sourceTurnId, String ticketNo) { }
}
