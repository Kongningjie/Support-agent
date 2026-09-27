package com.lawrence.supportagent.knowledgespace;

import java.time.Instant;
import java.util.UUID;

/** 不暴露密码、Token 或账号锁定细节的空间成员视图。 */
public record SpaceMembershipView(UUID userId, String username, String displayName,
                                  SpaceRole role, SpaceMembershipStatus status, long version,
                                  Instant createdAt, Instant updatedAt, Instant revokedAt) {
}
