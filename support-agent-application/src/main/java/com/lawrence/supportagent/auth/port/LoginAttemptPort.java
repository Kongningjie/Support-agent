package com.lawrence.supportagent.auth.port;

import java.time.Instant;
import java.util.Optional;

/** 隔离登录失败计数和短时限流状态。 */
public interface LoginAttemptPort {
    /** 判断规范化用户名或客户端来源任一主体是否仍被限流。 */
    Optional<Instant> blockedUntil(String username, String clientSource, Instant now);
    /** 原子记录一次失败并返回本次递增退避决定。 */
    LoginAttemptDecision recordFailure(String username, String clientSource, Instant now);
    /** 登录成功后分别清理当前用户名和来源的失败计数。 */
    void clear(String username, String clientSource);
    /** 管理员解锁时清除指定用户名的失败计数；来源级防护保持不变。 */
    void clearUsername(String username);

    /** @param failureCount 当前主体连续失败次数 @param blockedUntil 本次失败后的锁定截止时间，可空 */
    record LoginAttemptDecision(long failureCount, Instant blockedUntil) { }
}
