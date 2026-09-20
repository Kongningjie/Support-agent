package com.lawrence.supportagent.auth.port;

import java.time.Duration;

/** 隔离登录失败计数和短时限流状态。 */
public interface LoginAttemptPort {
    /** 判断规范化用户名或客户端来源任一主体是否仍被限流。 */
    boolean blocked(String username, String clientSource, int maximumFailures);
    /** 原子记录一次失败并按阈值维持限流窗口。 */
    void recordFailure(String username, String clientSource, int maximumFailures, Duration window);
    /** 登录成功后分别清理当前用户名和来源的失败计数。 */
    void clear(String username, String clientSource);
}
