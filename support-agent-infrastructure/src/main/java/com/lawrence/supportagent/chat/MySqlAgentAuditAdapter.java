package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.persistence.mapper.AgentAuditMapper;
import com.lawrence.supportagent.retrieval.BranchStatus;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** 通过 MyBatis 持久化不含用户原文、知识正文和模型输出的审计摘要。 */
public class MySqlAgentAuditAdapter implements AgentAuditPort {
    private final AgentAuditMapper mapper;
    private final ObjectMapper json;

    /** 注入审计 Mapper 和 JSON 序列化器。 */
    public MySqlAgentAuditAdapter(AgentAuditMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override public void start(UUID runId, UUID conversationId, UUID clientMessageId, Instant now) {
        mapper.insertRun(toBytes(runId), toBytes(conversationId), toBytes(clientMessageId), now);
    }
    /** {@inheritDoc} */
    @Override public void recordIntent(UUID runId, IntentDecision decision) {
        mapper.updateIntent(toBytes(runId), decision.intent().name(), decision.confidence(), decision.reasonCode());
    }
    /** {@inheritDoc} */
    @Override public void recordRetrieval(UUID runId, String standaloneQuery, RetrievalResult result,
                                          double groundedThreshold, Instant now) {
        String scores = write(result.candidates().stream().limit(30).map(this::score).toList());
        mapper.insertRetrieval(toBytes(runId), sha256(standaloneQuery), result.bm25Status().name(),
                result.vectorStatus().name(), result.rerankStatus().name(),
                result.rerankStatus() == BranchStatus.DEGRADED, groundedThreshold, scores,
                result.evidence().size(), result.status().name(), result.durationMs(), now);
    }
    /** {@inheritDoc} */
    @Override public void succeed(UUID runId, String promptVersion, String chatModel, String embeddingModel,
                                  String rerankModel, long durationMs, Instant now) {
        mapper.succeed(toBytes(runId), promptVersion, chatModel, embeddingModel, rerankModel, durationMs, now);
    }
    /** {@inheritDoc} */
    @Override public void fail(UUID runId, String status, String errorCode, long durationMs, Instant now) {
        mapper.fail(toBytes(runId), status, errorCode, durationMs, now);
    }

    /** 创建单个候选的不敏感分数摘要。 */
    private Map<String, Object> score(RetrievalEvidence value) {
        return Map.of("chunkId", value.chunkId(), "rrfScore", value.rrfScore(),
                "rerankScore", value.rerankScore() == null ? -1 : value.rerankScore());
    }
    /** 序列化候选安全摘要。 */
    private String write(Object value) { return json.writeValueAsString(value); }
    /** 计算独立检索问题 SHA-256，不保存原文。 */
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("JDK 不支持 SHA-256", exception); }
    }

    /** 把 UUID 稳定编码为 MySQL BINARY(16)，避免依赖 JDBC 驱动的 UUID 推断。 */
    private byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
