package com.lawrence.supportagent.config;

import com.lawrence.supportagent.chat.AnswerValidator;
import com.lawrence.supportagent.chat.ChatUseCase;
import com.lawrence.supportagent.chat.IntentRecognitionService;
import com.lawrence.supportagent.chat.MySqlAgentAuditAdapter;
import com.lawrence.supportagent.chat.RedisConversationStoreAdapter;
import com.lawrence.supportagent.chat.port.AgentAuditPort;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ElasticsearchKnowledgeIndexAdapter;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.MySqlKnowledgeSourceValidityAdapter;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.agent.model.DashScopeChatModelAdapter;
import com.lawrence.supportagent.agent.model.DashScopeIntentRecognitionAdapter;
import com.lawrence.supportagent.agent.model.UnavailableChatModelAdapter;
import com.lawrence.supportagent.agent.model.UnavailableIntentRecognitionAdapter;
import com.lawrence.supportagent.model.DashScopeRerankModelAdapter;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.IntentRecognitionPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.persistence.mapper.AgentAuditMapper;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** 装配阶段四聊天、混合检索、模型适配和会话审计能力。 */
@Configuration
public class ChatConfiguration {
    /** 创建独立意图模型端口。 */
    @Bean public IntentRecognitionPort intentRecognitionPort(SupportAgentProperties properties) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) return new UnavailableIntentRecognitionAdapter();
        return new DashScopeIntentRecognitionAdapter(config.apiKey(), config.chatModel(), config.baseUrl());
    }
    /** 创建内部流式 Chat 与受控工单 Agent 端口。 */
    @Bean public ChatModelPort chatModelPort(SupportAgentProperties properties) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) return new UnavailableChatModelAdapter();
        return new DashScopeChatModelAdapter(config.apiKey(), config.chatModel(), config.baseUrl());
    }
    /** 创建独立 Rerank 模型端口。 */
    @Bean public RerankModelPort rerankModelPort(SupportAgentProperties properties) {
        var config = properties.dashscope();
        return new DashScopeRerankModelAdapter(config.apiKey(), config.rerankModel(), config.baseUrl());
    }
    /** 创建会话和建议 Redis 适配器。 */
    @Bean public ConversationStorePort conversationStorePort(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisConversationStoreAdapter(redis, mapper);
    }
    /** 创建 MySQL Agent 安全审计适配器。 */
    @Bean public AgentAuditPort agentAuditPort(AgentAuditMapper mapper, ObjectMapper json) {
        return new MySqlAgentAuditAdapter(mapper, json);
    }
    /** 创建 MySQL 知识来源有效性回查端口。 */
    @Bean public KnowledgeSourceValidityPort knowledgeSourceValidityPort(
            ManagedDocumentRepository documents, ResolvedCaseRepository cases) {
        return new MySqlKnowledgeSourceValidityAdapter(documents, cases);
    }
    /** 创建混合检索编排器。 */
    @Bean(destroyMethod = "close") public RetrievalService retrievalService(
            ElasticsearchKnowledgeIndexAdapter search, KnowledgeSourceValidityPort validity,
            EmbeddingModelPort embedding, RerankModelPort rerank,
            @Value("${support-agent.retrieval.vector-minimum-similarity:0.20}") double similarity,
            @Value("${support-agent.retrieval.vector-candidates:200}") int candidates,
            @Value("${support-agent.retrieval.rerank-grounded-threshold:0.35}") double threshold) {
        return new RetrievalService(search, validity, embedding, rerank, similarity, candidates, threshold);
    }
    /** 创建意图识别服务。 */
    @Bean public IntentRecognitionService intentRecognitionService(IntentRecognitionPort model) {
        return new IntentRecognitionService(model, 0.70);
    }
    /** 创建确定性回答校验器。 */
    @Bean public AnswerValidator answerValidator(ExactTermExtractor extractor,
                                                 DocumentContentPolicy contentPolicy) {
        return new AnswerValidator(extractor, contentPolicy);
    }
    /** 创建阶段四聊天用例。 */
    @Bean public ChatUseCase chatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                                         ChatModelPort model, TicketQueryUseCase tickets,
                                         ConversationStorePort conversations, AgentAuditPort audits,
                                         AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                                         SupportAgentProperties properties,
                                         @Value("${support-agent.retrieval.rerank-grounded-threshold:0.35}")
                                         double threshold) {
        var config = properties.dashscope();
        return new ChatUseCase(intents, retrieval, model, tickets, conversations, audits, validator,
                ids, time, config.chatModel(), config.embeddingModel(), config.rerankModel(), threshold);
    }
}
