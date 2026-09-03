package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的工单数据记录，与领域聚合保持隔离。 */
public class TicketDO {
    /** MySQL 内部自增主键。 */
    public Long id;
    /** 对外工单编号。 */
    public String ticketNo;
    /** 来源会话 UUID 的 16 字节表示。 */
    public byte[] conversationId;
    /** 来源对话轮次 UUID 的 16 字节表示。 */
    public byte[] sourceTurnId;
    /** 工单标题。 */
    public String title;
    /** 问题现象与背景。 */
    public String problemDescription;
    /** 用户已经尝试的操作。 */
    public String attemptedActions;
    /** 工单状态枚举名称。 */
    public String status;
    /** 人工确认的根因。 */
    public String rootCause;
    /** 经验证的解决方案。 */
    public String solution;
    /** 未解决关闭的人工原因。 */
    public String closeReason;
    /** 乐观锁版本号。 */
    public long version;
    /** 创建操作者。 */
    public String createdBy;
    /** 创建 UTC 时间。 */
    public Instant createdAt;
    /** 最近修改操作者。 */
    public String updatedBy;
    /** 最近修改 UTC 时间。 */
    public Instant updatedAt;
    /** 解决操作者。 */
    public String resolvedBy;
    /** 解决 UTC 时间。 */
    public Instant resolvedAt;
    /** 关闭操作者。 */
    public String closedBy;
    /** 关闭 UTC 时间。 */
    public Instant closedAt;
}
