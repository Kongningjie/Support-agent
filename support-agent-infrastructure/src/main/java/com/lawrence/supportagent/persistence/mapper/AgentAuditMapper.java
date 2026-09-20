package com.lawrence.supportagent.persistence.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提供 Agent 运行和检索轨迹安全摘要的 MyBatis 写入语句。 */
@Mapper
public interface AgentAuditMapper {
    /** 创建 RUNNING 运行摘要。 */
    int insertRun(@Param("runId") byte[] runId, @Param("userId") byte[] userId,
                  @Param("conversationId") byte[] conversationId,
                  @Param("clientMessageId") byte[] clientMessageId, @Param("createdAt") Instant createdAt);
    /** 更新最终意图摘要。 */
    int updateIntent(@Param("runId") byte[] runId, @Param("intent") String intent,
                     @Param("confidence") double confidence, @Param("reasonCode") String reasonCode);
    /** 创建不含原始查询和正文的检索轨迹。 */
    int insertRetrieval(@Param("runId") byte[] runId, @Param("queryHash") String queryHash,
                        @Param("bm25Status") String bm25Status, @Param("vectorStatus") String vectorStatus,
                        @Param("rerankStatus") String rerankStatus, @Param("degraded") boolean degraded,
                        @Param("threshold") double threshold, @Param("scoresJson") String scoresJson,
                        @Param("selectedCount") int selectedCount, @Param("retrievalStatus") String retrievalStatus,
                        @Param("durationMs") long durationMs, @Param("createdAt") Instant createdAt);
    /** 标记运行成功。 */
    int succeed(@Param("runId") byte[] runId, @Param("promptVersion") String promptVersion,
                @Param("chatModel") String chatModel, @Param("embeddingModel") String embeddingModel,
                @Param("rerankModel") String rerankModel, @Param("durationMs") long durationMs,
                @Param("finishedAt") Instant finishedAt);
    /** 标记运行失败或中断。 */
    int fail(@Param("runId") byte[] runId, @Param("status") String status,
             @Param("errorCode") String errorCode, @Param("durationMs") long durationMs,
             @Param("finishedAt") Instant finishedAt);
}
