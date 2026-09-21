package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.user.UserAccount;
import java.util.Optional;
import java.util.UUID;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserStatus;
import java.util.List;

/** 隔离本地用户的 MySQL 持久化实现。 */
public interface UserRepository {
    /** 返回用户总数，用于首次管理员引导。 */
    long count();
    /** 按规范化用户名读取用户。 */
    Optional<UserAccount> findByUsername(String username);
    /** 按 MySQL 内部主键读取用户，仅用于幂等结果重放。 */
    Optional<UserAccount> findById(long id);
    /** 按公开 UUID 读取用户。 */
    Optional<UserAccount> findByUserId(UUID userId);
    /** 新增或按乐观锁更新用户。 */
    UserAccount save(UserAccount account);
    /** 按角色和状态可选筛选，以创建时间倒序分页。 */
    List<UserAccount> findPage(UserRole role, UserStatus status, int offset, int size);
    /** 返回相同筛选条件下的用户总数。 */
    long countPage(UserRole role, UserStatus status);
    /** 原子地把账号临时锁定时间单调延长，避免并发失败登录产生版本冲突。 */
    void extendLock(UUID userId, java.time.Instant lockedUntil, String operator,
                    java.time.Instant now);
    /** 仅在锁定已经到期时原子清空截止时间。 */
    void clearExpiredLock(UUID userId, java.time.Instant now, String operator);
}
