package com.lawrence.supportagent.feedback;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次成功回答的当前用户反馈，不包含原始回答或用户问题。
 *
 * @param runId 被评价的成功运行 UUID
 * @param userId 提交反馈的认证用户 UUID
 * @param rating 主观帮助程度
 * @param reason 无帮助时的预定义原因；有帮助时为空
 * @param note 可选的已校验说明；空白归一为空
 * @param version 乐观锁版本
 * @param createdAt 首次创建 UTC 时间
 * @param updatedAt 最近更正 UTC 时间
 */
public record AnswerFeedback(UUID runId, UUID userId, FeedbackRating rating,
                             FeedbackReason reason, String note, long version,
                             Instant createdAt, Instant updatedAt) { }
