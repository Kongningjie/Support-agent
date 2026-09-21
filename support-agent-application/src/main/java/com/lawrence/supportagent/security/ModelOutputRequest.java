package com.lawrence.supportagent.security;

import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import java.util.List;
import java.util.Objects;

/**
 * 描述统一安全网关检查模型完整输出所需的最小上下文。
 *
 * @param type 输出分支类型
 * @param output 待检查的完整输出
 * @param canary 当前调用泄漏标记，可为空
 * @param userMessage 当前用户消息，可为空
 * @param evidence 当前 RAG 证据，非 RAG 分支为空列表
 */
public record ModelOutputRequest(ModelOutputType type, String output, String canary,
                                 String userMessage, List<RetrievalEvidence> evidence) {
    /** 复制证据并拒绝缺失的输出类型。 */
    public ModelOutputRequest {
        type = Objects.requireNonNull(type, "模型输出类型不能为空");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
