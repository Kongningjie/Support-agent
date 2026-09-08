package com.lawrence.supportagent.chat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 表示接口层可序列化的单个 SSE 业务事件。
 *
 * @param eventId 事件 UUID
 * @param eventType 稳定事件类型
 * @param runId Agent 运行 UUID
 * @param conversationId 会话 UUID
 * @param sequence 会话内单调递增序号
 * @param timestamp 事件 UTC 时间
 * @param data 事件专属安全数据
 */
public record ChatEvent(UUID eventId, String eventType, UUID runId, UUID conversationId,
                        long sequence, Instant timestamp, Map<String, Object> data) { }
