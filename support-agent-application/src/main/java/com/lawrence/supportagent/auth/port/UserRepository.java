package com.lawrence.supportagent.auth.port;

import com.lawrence.supportagent.user.UserAccount;
import java.util.Optional;
import java.util.UUID;

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
}
