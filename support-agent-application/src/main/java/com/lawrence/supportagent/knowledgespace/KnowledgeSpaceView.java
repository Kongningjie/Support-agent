package com.lawrence.supportagent.knowledgespace;

import java.time.Instant;
import java.util.UUID;

/** 不含 MySQL 内部主键的知识空间安全视图。 */
public record KnowledgeSpaceView(UUID spaceId, String code, String name, String description,
                                 KnowledgeSpaceVisibility visibility, KnowledgeSpaceStatus status,
                                 boolean systemSpace, long version, SpaceRole currentUserRole,
                                 Instant createdAt, Instant updatedAt) {
    /** 从空间聚合与当前用户可空角色构造公开视图。 */
    public static KnowledgeSpaceView from(KnowledgeSpace value, SpaceRole role) {
        return new KnowledgeSpaceView(value.spaceId(), value.code(), value.name(),
                value.description(), value.visibility(), value.status(), value.systemSpace(),
                value.version(), role, value.createdAt(), value.updatedAt());
    }
}
