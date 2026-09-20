package com.lawrence.supportagent.chat;

import java.util.List;

/**
 * 单个会话详情。
 *
 * @param overview 会话公开元数据
 * @param recentTurns Redis 当前保留的最近成功轮次
 */
public record ConversationDetails(ConversationOverview overview, List<ConversationTurnView> recentTurns) {
    /** 复制最近轮次并拒绝缺少元数据的详情。 */
    public ConversationDetails {
        if (overview == null) throw new IllegalArgumentException("会话详情元数据不能为空");
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
