package com.lawrence.supportagent.chat;

import java.util.List;

/**
 * 表示单个 Redis 会话的结构化滚动摘要，不包含隐藏推理或知识正文。
 *
 * @param schemaVersion 摘要 JSON 结构版本
 * @param summaryVersion 摘要 CAS 修订版本
 * @param coveredThroughVersion 已被摘要覆盖的最后成功会话版本
 * @param goals 仍有效的用户目标
 * @param confirmedDecisions 用户已经确认且仍有效的决定
 * @param constraints 仍有效的技术、业务和安全约束
 * @param unresolvedQuestions 尚未解决或等待确认的问题
 * @param keyEntities 可回溯到来源轮次的精确技术实体
 */
public record ConversationSummary(String schemaVersion, long summaryVersion,
                                  long coveredThroughVersion, List<String> goals,
                                  List<String> confirmedDecisions, List<String> constraints,
                                  List<String> unresolvedQuestions,
                                  List<ConversationSummaryKeyEntity> keyEntities) {
    /** 当前冻结的摘要结构版本。 */
    public static final String SCHEMA_VERSION = "conversation-summary-v1";

    /** 规范化并复制全部集合，确保写入 Redis 后摘要内容不可变。 */
    public ConversationSummary {
        if (!SCHEMA_VERSION.equals(schemaVersion) || summaryVersion <= 0
                || coveredThroughVersion <= 0) {
            throw new IllegalArgumentException("摘要版本、结构版本或覆盖版本不合法");
        }
        goals = normalized(goals, "goals");
        confirmedDecisions = normalized(confirmedDecisions, "confirmedDecisions");
        constraints = normalized(constraints, "constraints");
        unresolvedQuestions = normalized(unresolvedQuestions, "unresolvedQuestions");
        keyEntities = keyEntities == null ? List.of() : List.copyOf(keyEntities);
    }

    /** 将结构化字段转换为稳定且不暴露内部版本的模型上下文文本。 */
    public String toModelContext() {
        return "较早会话摘要：\n"
                + section("用户目标", goals)
                + section("已确认决定", confirmedDecisions)
                + section("约束", constraints)
                + section("未解决问题", unresolvedQuestions)
                + section("关键实体", keyEntities.stream()
                        .map(entity -> entity.type().name() + "=" + entity.normalizedValue()).toList());
    }

    /** 复制、去除空白项并稳定去重单个摘要字符串集合。 */
    private static List<String> normalized(List<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return values.stream().map(String::strip).filter(value -> !value.isBlank())
                .distinct().toList();
    }

    /** 将一个结构化摘要分区渲染为紧凑的模型上下文。 */
    private String section(String title, List<String> values) {
        return values.isEmpty() ? "" : title + "：" + String.join("；", values) + "\n";
    }
}
