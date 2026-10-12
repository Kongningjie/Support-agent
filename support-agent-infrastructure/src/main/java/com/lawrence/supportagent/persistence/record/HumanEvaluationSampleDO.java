package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 人工评测样本持久化记录。 */
public class HumanEvaluationSampleDO {
    /** 公开样本 UUID 字节。 */ public byte[] sampleId;
    /** 来源类型。 */ public String sourceType;
    /** 来源标识。 */ public String sourceRef;
    /** 知识空间 UUID 字节。 */ public byte[] spaceId;
    /** 脱敏问题。 */ public String questionText;
    /** 脱敏回答。 */ public String answerText;
    /** 脱敏证据。 */ public String evidenceText;
    /** 创建管理员 UUID 字节。 */ public byte[] createdBy;
    /** 评审状态。 */ public String reviewStatus;
    /** 数据集层级。 */ public String datasetState;
    /** 最近数据集版本。 */ public long datasetVersion;
    /** 创建 UTC 时间。 */ public Instant createdAt;
}
