package com.lawrence.supportagent.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话列表和生命周期写操作返回的公开元数据。
 *
 * @param conversationId 公开会话 UUID
 * @param status 当前运行状态
 * @param version 已成功提交的轮次版本
 * @param generation 同一会话 ID 的重置代次
 * @param summaryVersion 当前滚动摘要修订版本
 * @param lastAccessAt 最近一次成功会话活动时间
 * @param expiresAt 按当前 Redis TTL 推算的预计过期时间
 */
public record ConversationOverview(UUID conversationId, ConversationLifecycleStatus status,
                                   long version, long generation, long summaryVersion,
                                   Instant lastAccessAt, Instant expiresAt) { }
