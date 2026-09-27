package com.lawrence.supportagent.retrieval;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpace;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 表示一次检索已经由服务端授权的主体、活动空间和允许空间集合。
 *
 * @param actor 当前仍有效的认证主体
 * @param activeSpaceId 当前会话代际绑定的活动空间
 * @param allowedSpaceIds 召回和来源回查共同使用的不可空空间集合
 */
public record RetrievalAccessContext(AuthenticatedUser actor, UUID activeSpaceId,
                                     Set<UUID> allowedSpaceIds) {
    /** 复制允许集合，并强制其严格等于 GLOBAL 加当前活动空间的最小集合。 */
    public RetrievalAccessContext {
        if (actor == null || activeSpaceId == null || allowedSpaceIds == null
                || allowedSpaceIds.isEmpty()) {
            throw new IllegalArgumentException("检索访问上下文不能为空");
        }
        allowedSpaceIds = Set.copyOf(new LinkedHashSet<>(allowedSpaceIds));
        Set<UUID> expected = activeSpaceId.equals(KnowledgeSpace.GLOBAL_SPACE_ID)
                ? Set.of(KnowledgeSpace.GLOBAL_SPACE_ID)
                : Set.of(KnowledgeSpace.GLOBAL_SPACE_ID, activeSpaceId);
        if (!allowedSpaceIds.equals(expected)) {
            throw new IllegalArgumentException("检索允许空间必须严格等于 GLOBAL 加活动空间");
        }
    }
}
