package com.lawrence.supportagent.feedback;

import java.util.Map;

/**
 * 管理员可见的低敏聚合计数，不包含用户、运行 ID 或说明正文。
 *
 * @param total 当前未删除反馈总数
 * @param byRating 每个帮助程度的计数，包含 0 值项
 * @param byReason 每个无帮助原因的计数，包含 0 值项
 */
public record FeedbackSummary(long total, Map<FeedbackRating, Long> byRating,
                              Map<FeedbackReason, Long> byReason) {
    /** 固定枚举键和快照，避免调用者修改聚合结果。 */
    public FeedbackSummary {
        byRating = Map.copyOf(byRating);
        byReason = Map.copyOf(byReason);
    }
}
