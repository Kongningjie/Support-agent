package com.lawrence.supportagent.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 仅检查 DashScope 密钥是否配置，不发起付费网络探测。 */
@Component("dashScopeConfigurationHealthIndicator")
public class DashScopeConfigurationHealthIndicator implements HealthIndicator {
    private final SupportAgentProperties properties;

    /** 保存只读配置用于健康判断。 */
    public DashScopeConfigurationHealthIndicator(SupportAgentProperties properties) {
        this.properties = properties;
    }

    /** 根据密钥是否存在返回健康状态，不暴露密钥内容。 */
    @Override
    public Health health() {
        String apiKey = properties.dashscope() == null ? null : properties.dashscope().apiKey();
        return apiKey == null || apiKey.isBlank()
                ? Health.unknown().withDetail("configured", false).build()
                : Health.up().withDetail("configured", true).build();
    }
}
