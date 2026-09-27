package com.lawrence.supportagent.knowledgespace;

import java.time.Instant;
import java.util.UUID;

/** 用户与知识空间的可撤销成员关系，维护角色和乐观锁版本。 */
public record SpaceMembership(Long id, UUID userId, UUID spaceId, SpaceRole role,
                              SpaceMembershipStatus status, long version, String createdBy,
                              Instant createdAt, String updatedBy, Instant updatedAt,
                              String revokedBy, Instant revokedAt) {
    /** 校验成员标识、角色、状态、版本与审计字段。 */
    public SpaceMembership {
        if (userId == null || spaceId == null || role == null || status == null || version < 0
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("成员用户、空间、角色、状态、版本和时间不能为空");
        }
        if (createdBy == null || createdBy.isBlank() || updatedBy == null || updatedBy.isBlank()) {
            throw new IllegalArgumentException("成员审计操作者不能为空");
        }
        if (status == SpaceMembershipStatus.REVOKED && (revokedBy == null || revokedAt == null)) {
            throw new IllegalArgumentException("已撤销成员必须记录撤销审计信息");
        }
    }

    /** 创建活动成员关系。 */
    public static SpaceMembership create(UUID userId, UUID spaceId, SpaceRole role,
                                         String operator, Instant now) {
        return new SpaceMembership(null, userId, spaceId, role, SpaceMembershipStatus.ACTIVE,
                0, operator, now, operator, now, null, null);
    }

    /** 修改活动成员角色并递增版本。 */
    public SpaceMembership changeRole(SpaceRole newRole, String operator, Instant now) {
        requireActive();
        if (newRole == null) {
            throw new IllegalArgumentException("空间角色不能为空");
        }
        return new SpaceMembership(id, userId, spaceId, newRole, status, version + 1,
                createdBy, createdAt, operator, now, null, null);
    }

    /** 恢复已撤销关系并设置新角色。 */
    public SpaceMembership restore(SpaceRole newRole, String operator, Instant now) {
        if (status != SpaceMembershipStatus.REVOKED || newRole == null) {
            throw new IllegalStateException("只有已撤销成员可以恢复");
        }
        return new SpaceMembership(id, userId, spaceId, newRole, SpaceMembershipStatus.ACTIVE,
                version + 1, createdBy, createdAt, operator, now, null, null);
    }

    /** 撤销活动成员关系并保留原角色用于审计。 */
    public SpaceMembership revoke(String operator, Instant now) {
        requireActive();
        return new SpaceMembership(id, userId, spaceId, role, SpaceMembershipStatus.REVOKED,
                version + 1, createdBy, createdAt, operator, now, operator, now);
    }

    /** 拒绝对非活动成员执行角色变化或重复撤销。 */
    private void requireActive() {
        if (status != SpaceMembershipStatus.ACTIVE) {
            throw new IllegalStateException("当前成员关系不是活动状态");
        }
    }
}
