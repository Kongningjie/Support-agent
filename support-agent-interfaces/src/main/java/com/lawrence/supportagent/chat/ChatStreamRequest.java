package com.lawrence.supportagent.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 聊天流请求 DTO。
 *
 * @param conversationId 首次为空，后续为服务端返回的会话 UUID
 * @param clientMessageId 当前用户消息的幂等 UUID
 * @param message 去除首尾空白后 1 至 4000 字符的消息
 * @param expectedConversationVersion 首次为空，后续为客户端持有的会话版本
 */
public record ChatStreamRequest(
        @Schema(description = "首次为空；后续填写服务端会话 UUID", example = "8e51b6d7-a9a9-4db1-b083-aec0fcfa3881")
        UUID conversationId,
        @NotNull @Schema(description = "本条消息的幂等 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID clientMessageId,
        @NotBlank @Size(max = 4000) @Schema(description = "用户消息，最多 4000 字符", example = "MySQL 连接超时怎么排查？")
        String message,
        @Schema(description = "后续消息必填的预期会话版本", example = "1")
        Long expectedConversationVersion) {
    /** 首次和后续会话字段必须成对为空或成对存在。 */
    @AssertTrue(message = "conversationId 与 expectedConversationVersion 必须同时为空或同时提供")
    public boolean isConversationVersionPairValid() {
        return (conversationId == null) == (expectedConversationVersion == null)
                && (expectedConversationVersion == null || expectedConversationVersion >= 0);
    }

    /** 转换为不依赖接口框架的应用请求。 */
    public ChatRequest toCommand() {
        return new ChatRequest(conversationId, clientMessageId, message, expectedConversationVersion);
    }
}
