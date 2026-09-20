package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.memory.UserMemoryContentPolicy.NormalizedMemory;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executor;

/** 在聊天成功后异步产生待用户确认的长期记忆候选。 */
public class UserMemoryCandidateService {
    private static final int MAXIMUM_MEMORIES_PER_USER = 100;
    private final UserMemoryRepository repository;
    private final UserMemoryCandidatePort model;
    private final UserMemoryContentPolicy contentPolicy;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final Executor executor;

    /** 注入设置与候选存储、独立模型、安全策略、标识、时钟和异步执行器。 */
    public UserMemoryCandidateService(UserMemoryRepository repository,
                                      UserMemoryCandidatePort model,
                                      UserMemoryContentPolicy contentPolicy,
                                      UuidGenerator ids, TimeProvider time, Executor executor) {
        this.repository = repository;
        this.model = model;
        this.contentPolicy = contentPolicy;
        this.ids = ids;
        this.time = time;
        this.executor = executor;
    }

    /** 用户开启记忆时调度本轮候选提取；调度或生成失败不得改变聊天结果。 */
    public void afterSuccessfulTurn(UUID userId, UUID conversationId,
                                    UUID clientMessageId, String userMessage) {
        try {
            if (!repository.findSettings(userId).map(UserMemorySettings::enabled).orElse(false)) {
                return;
            }
            executor.execute(() -> generate(userId, conversationId, clientMessageId, userMessage));
        } catch (RuntimeException ignored) {
            // 候选生成是聊天提交后的尽力能力，失败不回滚成功轮次。
        }
    }

    /** 调用独立模型并只保存通过确定性安全校验且未重复的候选。 */
    private void generate(UUID userId, UUID conversationId,
                          UUID clientMessageId, String userMessage) {
        try {
            if (repository.countByUser(userId) >= MAXIMUM_MEMORIES_PER_USER) {
                return;
            }
            List<UserMemoryCandidate> candidates = model.propose(userMessage);
            int remaining = (int) Math.min(MAXIMUM_MEMORIES_PER_USER - repository.countByUser(userId), 3);
            List<UserMemory> validated = new ArrayList<>();
            for (UserMemoryCandidate candidate : candidates.stream().limit(remaining).toList()) {
                NormalizedMemory normalized = contentPolicy.normalize(candidate.content());
                validated.add(UserMemory.propose(ids.generate(), userId,
                        candidate.memoryType(), normalized.content(), normalized.contentHash(),
                        conversationId, clientMessageId, time.now()));
            }
            for (UserMemory memory : validated) {
                repository.insertCandidate(memory);
            }
        } catch (RuntimeException ignored) {
            // 模型、Schema、安全校验或数据库故障均不得影响已经完成的聊天。
        }
    }
}
