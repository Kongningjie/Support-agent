package com.lawrence.supportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 汇总 Support Agent 自有配置，并与 Spring 基础设施配置隔离。
 *
 * @param operatorId 一期固定审计操作者标识
 * @param dashscope DashScope 凭据配置
 * @param elasticsearch Elasticsearch 健康探测连接配置
 */
@ConfigurationProperties(prefix = "support-agent")
public record SupportAgentProperties(String operatorId, DashScope dashscope,
                                     Elasticsearch elasticsearch) {
    /**
     * DashScope 配置；密钥为空表示当前环境未启用模型能力。
     *
     * @param apiKey DashScope API 密钥；不得写入仓库或日志
     */
    public record DashScope(String apiKey) { }

    /**
     * Elasticsearch 健康探测配置。
     *
     * @param url Elasticsearch 根地址
     * @param username 可选的基础认证用户名
     * @param password 可选的基础认证密码
     */
    public record Elasticsearch(String url, String username, String password) { }
}
