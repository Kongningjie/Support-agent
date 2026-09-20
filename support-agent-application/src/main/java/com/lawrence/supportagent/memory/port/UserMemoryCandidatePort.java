package com.lawrence.supportagent.memory.port;

import com.lawrence.supportagent.memory.UserMemoryCandidate;
import java.util.List;

/** 隔离长期记忆候选模型，不复用 Chat 或会话摘要端口。 */
public interface UserMemoryCandidatePort {
    /** 从单个成功用户消息中提取零到三条安全、简短的候选。 */
    List<UserMemoryCandidate> propose(String userMessage);
}
