package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.memory.UserMemoryCandidate;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.model.ModelInvocationException;
import java.util.List;

/** 在未配置 DashScope 密钥时拒绝真实长期记忆候选调用。 */
public class UnavailableUserMemoryCandidateAdapter implements UserMemoryCandidatePort {
    /** {@inheritDoc} */
    @Override public List<UserMemoryCandidate> propose(String userMessage) {
        throw new ModelInvocationException("DASHSCOPE_NOT_CONFIGURED",
                "当前环境未配置长期记忆候选模型", false, null);
    }
}
