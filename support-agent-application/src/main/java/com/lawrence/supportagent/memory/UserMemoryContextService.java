package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 在独立 Token 预算内选择并渲染当前用户已经确认的跨会话长期记忆。 */
public class UserMemoryContextService {
    private final UserMemoryRepository repository;
    private final ConservativeTokenEstimator tokens;
    private final TimeProvider time;
    private final int tokenBudget;

    /** 注入记忆仓库、确定性估算器、时钟和独立 Token 预算。 */
    public UserMemoryContextService(UserMemoryRepository repository,
                                    ConservativeTokenEstimator tokens,
                                    TimeProvider time, int tokenBudget) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("长期记忆 Token 预算必须大于 0");
        }
        this.repository = repository;
        this.tokens = tokens;
        this.time = time;
        this.tokenBudget = tokenBudget;
    }

    /** 按固定、类别和更新时间优先级选择不会超过预算的有效记忆。 */
    public List<String> contextFor(UUID userId) {
        if (userId == null || !repository.findSettings(userId).map(UserMemorySettings::enabled)
                .orElse(false)) {
            return List.of();
        }
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (UserMemory memory : repository.findActive(userId, time.now(), 100)) {
            String rendered = "[长期记忆|" + memory.memoryType().name() + "] " + memory.content();
            int required = tokens.estimate(rendered);
            if (used + required <= tokenBudget) {
                selected.add(rendered);
                used += required;
            }
        }
        return List.copyOf(selected);
    }
}
