package com.lawrence.supportagent.config;

import com.lawrence.supportagent.agent.model.GroundedPromptVariant;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 汇总 Support Agent 自有配置，并与 Spring 基础设施配置隔离。
 *
 * @param operatorId 一期固定审计操作者标识
 * @param dashscope DashScope 凭据和模型配置
 * @param elasticsearch Elasticsearch 连接和知识索引配置
 */
@ConfigurationProperties(prefix = "support-agent")
public record SupportAgentProperties(String operatorId, DashScope dashscope,
                                     Elasticsearch elasticsearch) {
    /** 校验应用自有配置的必填结构，避免错误配置延迟到首次请求才暴露。 */
    public SupportAgentProperties {
        requireText(operatorId, "support-agent.operator-id");
        Objects.requireNonNull(dashscope, "support-agent.dashscope 不能为空");
        Objects.requireNonNull(elasticsearch, "support-agent.elasticsearch 不能为空");
    }

    /**
     * DashScope 配置；密钥为空表示当前环境未启用模型能力。
     *
     * @param apiKey DashScope API 密钥；不得写入仓库或日志
     * @param baseUrl DashScope 原生 API 根地址
     * @param chatModel 对话与结构化生成模型名称
     * @param intentModel 独立意图识别模型名称
     * @param summaryModel 独立单会话滚动摘要模型名称
     * @param memoryModel 独立长期记忆候选模型名称
     * @param embeddingModel 文档与查询向量模型名称
     * @param rerankModel 候选重排模型名称
     * @param chatTimeout 普通 Chat 完整生成超时
     * @param intentTimeout 意图识别总超时
     * @param summaryTimeout 单次会话摘要完整生成超时
     * @param memoryTimeout 单次长期记忆候选生成超时
     * @param ticketTimeout 工单结构化生成超时
     * @param resolvedCaseTimeout 案例结构化生成超时
     * @param embeddingTimeout 单批 Embedding 调用总超时
     * @param rerankTimeout 单次 Rerank 调用读取超时
     * @param retryMaxAttempts Embedding 和 Rerank 安全幂等调用的最大尝试次数
     * @param retryInitialDelay 模型安全重试的初始退避时间
     * @param chatMaxOutputTokens 普通 Chat 最大输出 Token
     * @param intentMaxOutputTokens 意图识别最大输出 Token
     * @param summaryMaxOutputTokens 单次结构化会话摘要最大输出 Token
     * @param memoryMaxOutputTokens 单次长期记忆候选最大输出 Token
     * @param structuredMaxOutputTokens 工单和案例结构化生成最大输出 Token
     * @param groundedPromptVariant 知识回答 Prompt A/B 版本
     */
    public record DashScope(String apiKey, String baseUrl, String chatModel, String intentModel,
                            String summaryModel, String memoryModel,
                            String embeddingModel, String rerankModel, Duration chatTimeout,
                            Duration intentTimeout, Duration summaryTimeout, Duration memoryTimeout,
                            Duration ticketTimeout,
                            Duration resolvedCaseTimeout, Duration embeddingTimeout,
                            Duration rerankTimeout, int retryMaxAttempts,
                            Duration retryInitialDelay, int chatMaxOutputTokens,
                            int intentMaxOutputTokens, int summaryMaxOutputTokens,
                            int memoryMaxOutputTokens,
                            int structuredMaxOutputTokens,
                            GroundedPromptVariant groundedPromptVariant) {
        /** 校验 DashScope 地址、模型、超时、重试和输出上限。 */
        public DashScope {
            requireHttpUrl(baseUrl, "support-agent.dashscope.base-url");
            requireText(chatModel, "support-agent.dashscope.chat-model");
            requireText(intentModel, "support-agent.dashscope.intent-model");
            requireText(summaryModel, "support-agent.dashscope.summary-model");
            requireText(memoryModel, "support-agent.dashscope.memory-model");
            requireText(embeddingModel, "support-agent.dashscope.embedding-model");
            requireText(rerankModel, "support-agent.dashscope.rerank-model");
            requirePositive(chatTimeout, "support-agent.dashscope.chat-timeout");
            requirePositive(intentTimeout, "support-agent.dashscope.intent-timeout");
            requirePositive(summaryTimeout, "support-agent.dashscope.summary-timeout");
            requirePositive(memoryTimeout, "support-agent.dashscope.memory-timeout");
            requirePositive(ticketTimeout, "support-agent.dashscope.ticket-timeout");
            requirePositive(resolvedCaseTimeout, "support-agent.dashscope.resolved-case-timeout");
            requirePositive(embeddingTimeout, "support-agent.dashscope.embedding-timeout");
            requirePositive(rerankTimeout, "support-agent.dashscope.rerank-timeout");
            requirePositive(retryInitialDelay, "support-agent.dashscope.retry-initial-delay");
            if (retryMaxAttempts < 1 || retryMaxAttempts > 3) {
                throw new IllegalArgumentException(
                        "support-agent.dashscope.retry-max-attempts 必须在 1 到 3 之间");
            }
            requireRange(chatMaxOutputTokens, 1, 4096,
                    "support-agent.dashscope.chat-max-output-tokens");
            requireRange(intentMaxOutputTokens, 1, 1024,
                    "support-agent.dashscope.intent-max-output-tokens");
            requireRange(summaryMaxOutputTokens, 1, 4096,
                    "support-agent.dashscope.summary-max-output-tokens");
            requireRange(memoryMaxOutputTokens, 1, 4096,
                    "support-agent.dashscope.memory-max-output-tokens");
            requireRange(structuredMaxOutputTokens, 1, 4096,
                    "support-agent.dashscope.structured-max-output-tokens");
            Objects.requireNonNull(groundedPromptVariant,
                    "support-agent.dashscope.grounded-prompt-variant 不能为空");
        }
    }

    /**
     * Elasticsearch 健康探测配置。
     *
     * @param url Elasticsearch 根地址
     * @param username 可选的基础认证用户名
     * @param password 可选的基础认证密码
     * @param knowledgeIndex 阶段 3 知识物理索引名称
     * @param knowledgeAlias 面向业务读写的固定知识别名
     * @param connectTimeout 建立 Elasticsearch TCP 连接的最长等待时间
     * @param connectionRequestTimeout 从连接池获取连接的最长等待时间
     * @param responseTimeout 等待 Elasticsearch 完整响应的最长时间
     */
    public record Elasticsearch(String url, String username, String password,
                                String knowledgeIndex, String knowledgeAlias,
                                Duration connectTimeout, Duration connectionRequestTimeout,
                                Duration responseTimeout) {
        /** 校验 Elasticsearch 地址、索引标识和三个独立超时。 */
        public Elasticsearch {
            requireHttpUrl(url, "support-agent.elasticsearch.url");
            requireText(knowledgeIndex, "support-agent.elasticsearch.knowledge-index");
            requireText(knowledgeAlias, "support-agent.elasticsearch.knowledge-alias");
            requirePositive(connectTimeout, "support-agent.elasticsearch.connect-timeout");
            requirePositive(connectionRequestTimeout,
                    "support-agent.elasticsearch.connection-request-timeout");
            requirePositive(responseTimeout, "support-agent.elasticsearch.response-timeout");
        }
    }

    /** 校验文本配置存在且去除空白后仍有内容。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /** 校验外部服务根地址使用明确的 HTTP 或 HTTPS 协议及主机名。 */
    private static void requireHttpUrl(String value, String name) {
        requireText(value, name);
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " 必须是包含主机名的 HTTP(S) 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 必须是合法的 HTTP(S) 地址", exception);
        }
    }

    /** 校验时长为正值且不超过十分钟，防止配置导致无限等待。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(name + " 必须大于 0 且不超过 10 分钟");
        }
    }

    /** 校验整数配置处于闭区间。 */
    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }
}
