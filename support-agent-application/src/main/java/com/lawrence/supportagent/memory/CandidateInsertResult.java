package com.lawrence.supportagent.memory;

import java.util.Objects;
import java.util.Optional;

/** 携带候选写入分类及仅在成功时存在的已持久化聚合。 */
public record CandidateInsertResult(CandidateInsertOutcome outcome, Optional<UserMemory> memory) {
    /** 校验结果分类与聚合存在性保持一致。 */
    public CandidateInsertResult {
        Objects.requireNonNull(outcome, "候选写入结果不能为空");
        memory = memory == null ? Optional.empty() : memory;
        if ((outcome == CandidateInsertOutcome.INSERTED) != memory.isPresent()) {
            throw new IllegalArgumentException("只有 INSERTED 结果可以携带已持久化记忆");
        }
    }

    /** 创建成功写入结果。 */
    public static CandidateInsertResult inserted(UserMemory memory) {
        return new CandidateInsertResult(CandidateInsertOutcome.INSERTED,
                Optional.of(Objects.requireNonNull(memory, "已写入记忆不能为空")));
    }

    /** 创建未写入结果。 */
    public static CandidateInsertResult rejected(CandidateInsertOutcome outcome) {
        if (outcome == CandidateInsertOutcome.INSERTED) {
            throw new IllegalArgumentException("INSERTED 必须携带已持久化记忆");
        }
        return new CandidateInsertResult(outcome, Optional.empty());
    }
}
