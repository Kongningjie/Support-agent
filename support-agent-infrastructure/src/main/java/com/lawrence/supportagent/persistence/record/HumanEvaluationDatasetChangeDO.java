package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** 单次数据集纳入或晋升的版本和审计记录。 */
public class HumanEvaluationDatasetChangeDO {
    /** 全局自动递增的数据集版本。 */ public long version;
    /** 样本 UUID 字节。 */ public byte[] sampleId;
    /** 操作管理员 UUID 字节。 */ public byte[] actorId;
    /** 旧层级。 */ public String fromState;
    /** 新层级。 */ public String toState;
    /** 晋升 UTC 时间。 */ public Instant changedAt;
}
