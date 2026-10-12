package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 人工盲评分持久化记录。 */
public class HumanEvaluationReviewDO {
    /** 样本 UUID 字节。 */ public byte[] sampleId;
    /** 评审人 UUID 字节。 */ public byte[] reviewerId;
    /** FIRST、SECOND 或 ARBITER。 */ public String reviewRole;
    /** 事实正确分。 */ public int factual;
    /** 引用支持分。 */ public int citation;
    /** 完整性分。 */ public int completeness;
    /** 可操作性分。 */ public int actionability;
    /** 清晰度分。 */ public int clarity;
    /** 评分理由。 */ public String reason;
    /** 提交 UTC 时间。 */ public Instant createdAt;
}
