package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import java.util.UUID;

/**
 * 表示聊天 SSE 用例输入。
 *
 * @param actor 当前已认证用户
 * @param conversationId 首次为空、后续必填的会话 UUID
 * @param clientMessageId 当前用户消息幂等 UUID
 * @param message 用户消息
 * @param expectedConversationVersion 后续会话必填的预期版本
 */
public record ChatRequest(AuthenticatedUser actor, UUID conversationId, UUID clientMessageId, String message,
                          Long expectedConversationVersion) { }
