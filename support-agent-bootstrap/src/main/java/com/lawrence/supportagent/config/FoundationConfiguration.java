package com.lawrence.supportagent.config;

import com.lawrence.supportagent.sharedkernel.OperatorId;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配统一时间、标识、操作者及生产密钥校验。 */
@Configuration
@EnableConfigurationProperties(SupportAgentProperties.class)
public class FoundationConfiguration {
    /** 使用系统 UTC 时钟提供当前时间。 */
    @Bean
    public TimeProvider timeProvider() {
        return Instant::now;
    }

    /** 使用 JDK 安全随机源生成 UUID。 */
    @Bean
    public UuidGenerator uuidGenerator() {
        return UUID::randomUUID;
    }

    /** 返回服务端配置的固定操作者。 */
    @Bean
    public OperatorProvider operatorProvider(SupportAgentProperties properties) {
        OperatorId operatorId = new OperatorId(properties.operatorId());
        return () -> operatorId;
    }

    /** 在生产 Profile 缺少 DashScope 密钥时阻止应用启动。 */
    @Bean
    public InitializingBean productionDashScopeValidator(SupportAgentProperties properties,
                                                          Environment environment) {
        return () -> {
            boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            String apiKey = properties.dashscope() == null ? null : properties.dashscope().apiKey();
            if (production && (apiKey == null || apiKey.isBlank())) {
                throw new IllegalStateException("生产环境必须配置 DASHSCOPE_API_KEY");
            }
        };
    }
}
