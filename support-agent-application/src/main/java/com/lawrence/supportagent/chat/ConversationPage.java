package com.lawrence.supportagent.chat;

import java.util.List;

/**
 * 应用层会话分页结果。
 *
 * @param items 当前页会话
 * @param page 从 1 开始的页码
 * @param size 每页数量
 * @param totalElements 会话总数
 * @param totalPages 总页数
 */
public record ConversationPage(List<ConversationOverview> items, int page, int size,
                               long totalElements, int totalPages) {
    /** 复制结果并校验分页元数据。 */
    public ConversationPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 1 || size < 1 || size > 100 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("会话分页元数据不合法");
        }
    }
}
