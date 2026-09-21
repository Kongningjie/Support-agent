package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.auth.ExternalSubject;
import java.util.Optional;
import java.util.UUID;

/** 定义未来外部主体到本地账号的显式关联边界，本阶段不提供真实适配器。 */
public interface ExternalIdentityMappingPort {
    /** 查询已经人工建立关联的本地公开用户标识。 */
    Optional<UUID> findLocalUserId(ExternalSubject subject);
}
