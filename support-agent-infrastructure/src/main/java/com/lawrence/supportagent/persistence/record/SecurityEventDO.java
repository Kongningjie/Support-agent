package com.lawrence.supportagent.persistence.record;

import java.time.Instant;

/** MyBatis 使用的安全事件数据记录，不包含密码、Token 或请求正文。 */
public class SecurityEventDO {
    /** 稳定事件类型。 */ public String eventType;
    /** 可空目标用户 UUID 字节。 */ public byte[] targetUserId;
    /** 操作者公开标识或稳定系统身份。 */ public String actorId;
    /** SUCCEEDED 或 DENIED。 */ public String result;
    /** 低基数原因分类。 */ public String reason;
    /** 可空来源 SHA-256。 */ public String sourceHash;
    /** UTC 发生时间。 */ public Instant occurredAt;
}
