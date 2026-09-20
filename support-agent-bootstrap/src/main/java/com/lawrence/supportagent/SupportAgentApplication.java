package com.lawrence.supportagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Support Agent 单体应用启动入口。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class SupportAgentApplication {

    /**
     * 启动 Support Agent Spring Boot 应用。
     *
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SupportAgentApplication.class, args);
    }
}
