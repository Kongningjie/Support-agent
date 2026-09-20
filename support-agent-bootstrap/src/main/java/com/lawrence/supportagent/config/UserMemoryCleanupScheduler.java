package com.lawrence.supportagent.config;

import com.lawrence.supportagent.memory.UserMemoryCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按固定延迟触发单批过期待确认长期记忆候选清理。 */
@Component
public class UserMemoryCleanupScheduler {
    private final UserMemoryCleanupService cleanupService;

    /** 注入不向外传播清理故障的应用服务。 */
    public UserMemoryCleanupScheduler(UserMemoryCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 每个配置周期清理一批候选，多实例互斥由 MySQL 行锁保证。 */
    @Scheduled(fixedDelayString = "${support-agent.memory.cleanup-interval:1h}")
    public void cleanup() {
        cleanupService.cleanupOnce();
    }
}
