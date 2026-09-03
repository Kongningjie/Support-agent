package com.lawrence.supportagent.observability;

import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配接口层追踪基础设施，不包含业务 Controller。 */
@Configuration
public class WebFoundationConfiguration {
    /** 注册每请求一次的 traceId 过滤器。 */
    @Bean
    public TraceIdFilter traceIdFilter(UuidGenerator uuidGenerator) {
        return new TraceIdFilter(uuidGenerator);
    }
}
