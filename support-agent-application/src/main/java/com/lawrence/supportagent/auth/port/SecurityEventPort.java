package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.auth.SecurityEventType;
import java.time.Instant;
import java.util.UUID;

/** 隔离不含秘密和请求正文的安全事件审计持久化。 */
public interface SecurityEventPort {
    /** 写入一条稳定分类的账号安全事件。 */
    void record(SecurityEventType type, UUID targetUserId, String actorId, String result,
                String reason, String sourceHash, Instant occurredAt);
}
