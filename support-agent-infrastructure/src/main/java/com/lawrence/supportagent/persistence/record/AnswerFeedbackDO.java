package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 映射的用户回答反馈记录，不复用应用层模型。 */
public class AnswerFeedbackDO {
    /** 被评价的运行 UUID 字节。 */
    public byte[] runId;
    /** 提交用户 UUID 字节。 */
    public byte[] userId;
    /** 帮助程度稳定值。 */
    public String rating;
    /** 无帮助原因稳定值，可为空。 */
    public String reason;
    /** 用户可选说明，可为空。 */
    public String note;
    /** 乐观锁版本。 */
    public long version;
    /** 创建 UTC 时间。 */
    public Instant createdAt;
    /** 最近更正 UTC 时间。 */
    public Instant updatedAt;
}
