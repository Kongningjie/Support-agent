package com.lawrence.supportagent.memory;

/** 模型输出且尚未持久化的单条长期记忆候选。 */
public record UserMemoryCandidate(MemoryType memoryType, String content) {
    /** 拒绝缺少类别或正文的模型候选。 */
    public UserMemoryCandidate {
        if (memoryType == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("记忆候选类型和正文不能为空");
        }
    }
}
