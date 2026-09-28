package com.lawrence.supportagent.config;

import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceUseCase;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

/** 装配知识空间访问判定和治理用例。 */
@Configuration
public class KnowledgeSpaceConfiguration {
    /** 创建统一空间访问判定服务。 */
    @Bean
    public KnowledgeSpaceAccessService knowledgeSpaceAccessService(
            KnowledgeSpaceRepository spaces, SpaceMembershipRepository memberships,
            UserRepository users, KnowledgeSpaceTelemetryPort telemetry,
            SecurityEventPort events, TimeProvider time) {
        return new KnowledgeSpaceAccessService(spaces, memberships, users,
                telemetry, events, time);
    }

    /** 创建不携带业务标识和正文的知识空间低基数遥测端口。 */
    @Bean
    public KnowledgeSpaceTelemetryPort knowledgeSpaceTelemetryPort(MeterRegistry registry) {
        return new MicrometerKnowledgeSpaceTelemetryAdapter(registry);
    }

    /** 创建空间查询、治理、成员、幂等和审计用例。 */
    @Bean
    public KnowledgeSpaceUseCase knowledgeSpaceUseCase(
            KnowledgeSpaceRepository spaces, SpaceMembershipRepository memberships,
            UserRepository users, KnowledgeSpaceAccessService access,
            IdempotentExecutor idempotency, UuidGenerator ids, TimeProvider time,
            SecurityEventPort events) {
        return new KnowledgeSpaceUseCase(spaces, memberships, users, access,
                idempotency, ids, time, events);
    }
}
