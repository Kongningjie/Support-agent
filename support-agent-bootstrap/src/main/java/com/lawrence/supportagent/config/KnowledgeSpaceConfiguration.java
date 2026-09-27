package com.lawrence.supportagent.config;

import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceUseCase;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.knowledgespace.port.SpaceMembershipRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配知识空间访问判定和治理用例。 */
@Configuration
public class KnowledgeSpaceConfiguration {
    /** 创建统一空间访问判定服务。 */
    @Bean
    public KnowledgeSpaceAccessService knowledgeSpaceAccessService(
            KnowledgeSpaceRepository spaces, SpaceMembershipRepository memberships,
            UserRepository users) {
        return new KnowledgeSpaceAccessService(spaces, memberships, users);
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
