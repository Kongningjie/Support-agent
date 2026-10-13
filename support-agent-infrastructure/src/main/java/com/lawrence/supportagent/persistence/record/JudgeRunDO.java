package com.lawrence.supportagent.persistence.record;
import java.time.Instant;
/** Judge MyBatis 记录；JSON 仅含已校验结构化结果。 */
public class JudgeRunDO {
    /** 运行 UUID。 */ public String runId;
    /** 样本 UUID。 */ public String sampleId;
    /** 操作管理员 UUID。 */ public String actorId;
    /** 幂等 UUID。 */ public String requestKey;
    /** 模型名。 */ public String modelName;
    /** Prompt/Schema 版本。 */ public String promptVersion;
    /** 执行状态。 */ public String status;
    /** 输入预估 Token。 */ public int estimatedInputTokens;
    /** UTC 开始时间。 */ public Instant startedAt;
    /** UTC 租约截止。 */ public Instant expiresAt;
    /** 已校验结果 JSON。 */ public String attemptsJson;
}
