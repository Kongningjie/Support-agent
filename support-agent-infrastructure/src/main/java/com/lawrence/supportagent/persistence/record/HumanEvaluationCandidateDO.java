package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** 仅含必要标识、原因和时间的无帮助反馈候选记录。 */
public class HumanEvaluationCandidateDO {
    /** 成功运行 UUID 字节。 */ public byte[] runId;
    /** 所属会话 UUID 字节。 */ public byte[] conversationId;
    /** 无帮助原因。 */ public String reason;
    /** 反馈时间。 */ public Instant createdAt;
}
