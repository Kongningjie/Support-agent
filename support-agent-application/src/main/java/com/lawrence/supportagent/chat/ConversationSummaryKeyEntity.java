package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.knowledge.ExactTermType;
import java.util.List;

/**
 * 描述滚动摘要中可由原始成功轮次回溯验证的精确技术实体。
 *
 * @param type 一期冻结的精确词类型
 * @param normalizedValue 用于稳定比较的规范值
 * @param sourceTurnVersions 实体出现的成功会话版本
 */
public record ConversationSummaryKeyEntity(ExactTermType type, String normalizedValue,
                                           List<Long> sourceTurnVersions) {
    /** 复制来源版本并拒绝缺失的类型、值或来源，防止模型实体失去追溯依据。 */
    public ConversationSummaryKeyEntity {
        if (type == null || normalizedValue == null || normalizedValue.isBlank()
                || sourceTurnVersions == null || sourceTurnVersions.isEmpty()
                || sourceTurnVersions.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("摘要关键实体缺少类型、规范值或有效来源版本");
        }
        sourceTurnVersions = sourceTurnVersions.stream().distinct().sorted().toList();
    }
}
