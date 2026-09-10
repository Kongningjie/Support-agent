package com.lawrence.supportagent.config;

import com.lawrence.supportagent.agent.model.GroundedPromptVariant;
import java.time.Duration;
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
    /**
     * DashScope 配置；密钥为空表示当前环境未启用模型能力。
     *
     * @param apiKey DashScope API 密钥；不得写入仓库或日志
     * @param baseUrl DashScope 原生 API 根地址
     * @param chatModel 对话与结构化生成模型名称
     * @param intentModel 独立意图识别模型名称
     * @param embeddingModel 文档与查询向量模型名称
     * @param rerankModel 候选重排模型名称
     * @param chatTimeout 普通 Chat 完整生成超时
     * @param intentTimeout 意图识别总超时
     * @param ticketTimeout 工单结构化生成超时
     * @param resolvedCaseTimeout 案例结构化生成超时
     * @param chatMaxOutputTokens 普通 Chat 最大输出 Token
     * @param intentMaxOutputTokens 意图识别最大输出 Token
     * @param structuredMaxOutputTokens 工单和案例结构化生成最大输出 Token
     * @param groundedPromptVariant 知识回答 Prompt A/B 版本
     */
    public record DashScope(String apiKey, String baseUrl, String chatModel, String intentModel,
                            String embeddingModel, String rerankModel, Duration chatTimeout,
                            Duration intentTimeout, Duration ticketTimeout,
                            Duration resolvedCaseTimeout, int chatMaxOutputTokens,
                            int intentMaxOutputTokens, int structuredMaxOutputTokens,
                            GroundedPromptVariant groundedPromptVariant) { }

    /**
     * Elasticsearch 健康探测配置。
     *
     * @param url Elasticsearch 根地址
     * @param username 可选的基础认证用户名
     * @param password 可选的基础认证密码
     * @param knowledgeIndex 阶段 3 知识物理索引名称
     * @param knowledgeAlias 面向业务读写的固定知识别名
     */
    public record Elasticsearch(String url, String username, String password,
                                String knowledgeIndex, String knowledgeAlias) { }
}
