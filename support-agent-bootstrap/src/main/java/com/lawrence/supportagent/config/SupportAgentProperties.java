package com.lawrence.supportagent.config;

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
     * @param chatModel 对话与意图模型名称
     * @param embeddingModel 文档与查询向量模型名称
     * @param rerankModel 候选重排模型名称
     */
    public record DashScope(String apiKey, String baseUrl, String chatModel,
                            String embeddingModel, String rerankModel) { }

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
