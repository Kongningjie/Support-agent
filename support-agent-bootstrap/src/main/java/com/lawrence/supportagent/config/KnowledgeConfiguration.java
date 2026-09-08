package com.lawrence.supportagent.config;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.knowledge.DocumentChunker;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ElasticsearchKnowledgeIndexAdapter;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.KnowledgeDeleteTaskHandler;
import com.lawrence.supportagent.knowledge.KnowledgeIndexTaskHandler;
import com.lawrence.supportagent.knowledge.ManagedDocumentCommandUseCase;
import com.lawrence.supportagent.knowledge.ManagedDocumentQueryUseCase;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.model.DashScopeEmbeddingModelAdapter;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 装配阶段 3 托管知识、Embedding 和 Elasticsearch 适配器。 */
@Configuration
public class KnowledgeConfiguration {
    /** 创建统一正文规范化和敏感凭据扫描策略。 */
    @Bean
    public DocumentContentPolicy documentContentPolicy() {
        return new DocumentContentPolicy();
    }

    /** 创建一期八类精确技术词提取器。 */
    @Bean
    public ExactTermExtractor exactTermExtractor() {
        return new ExactTermExtractor();
    }

    /** 创建确定性 Markdown/TXT 分块器。 */
    @Bean
    public DocumentChunker documentChunker(ExactTermExtractor extractor) {
        return new DocumentChunker(extractor);
    }

    /** 创建托管文档查询用例。 */
    @Bean
    public ManagedDocumentQueryUseCase managedDocumentQueryUseCase(
            ManagedDocumentRepository repository) {
        return new ManagedDocumentQueryUseCase(repository);
    }

    /** 创建托管文档命令用例。 */
    @Bean
    public ManagedDocumentCommandUseCase managedDocumentCommandUseCase(
            ManagedDocumentRepository repository, AsyncTaskRepository taskRepository,
            ManagedDocumentQueryUseCase queryUseCase, AsyncTaskCreator taskCreator,
            IdempotentExecutor idempotentExecutor, OperatorProvider operatorProvider,
            TimeProvider timeProvider, DocumentContentPolicy contentPolicy) {
        return new ManagedDocumentCommandUseCase(repository, taskRepository, queryUseCase,
                taskCreator, idempotentExecutor, operatorProvider, timeProvider, contentPolicy);
    }

    /** 创建不会在启动时访问远端的 DashScope Embedding 独立适配器。 */
    @Bean(destroyMethod = "close")
    public EmbeddingModelPort embeddingModelPort(SupportAgentProperties properties) {
        SupportAgentProperties.DashScope config = properties.dashscope();
        return new DashScopeEmbeddingModelAdapter(config.apiKey(), config.embeddingModel(), config.baseUrl());
    }

    /** 创建 Elasticsearch REST5 客户端；索引仍由首个任务惰性检查。 */
    @Bean(destroyMethod = "close")
    public Rest5Client elasticsearchRest5Client(SupportAgentProperties properties) {
        return Rest5Client.builder(URI.create(properties.elasticsearch().url())).build();
    }

    /** 创建知识索引持久化端口。 */
    @Bean
    public ElasticsearchKnowledgeIndexAdapter knowledgeIndexPort(Rest5Client client, ObjectMapper objectMapper,
                                                                  SupportAgentProperties properties) {
        SupportAgentProperties.Elasticsearch config = properties.elasticsearch();
        return new ElasticsearchKnowledgeIndexAdapter(client, objectMapper,
                config.knowledgeIndex(), config.knowledgeAlias(),
                config.username(), config.password());
    }

    /** 注册托管文档异步索引任务处理器。 */
    @Bean
    public KnowledgeIndexTaskHandler knowledgeIndexTaskHandler(
            ManagedDocumentRepository repository, DocumentContentPolicy contentPolicy,
            DocumentChunker chunker, EmbeddingModelPort embeddingModel,
            KnowledgeIndexPort indexPort, TimeProvider timeProvider) {
        return new KnowledgeIndexTaskHandler(repository, contentPolicy, chunker,
                embeddingModel, indexPort, timeProvider);
    }

    /** 注册归档文档的 Elasticsearch 删除任务处理器。 */
    @Bean
    public KnowledgeDeleteTaskHandler knowledgeDeleteTaskHandler(
            ManagedDocumentRepository repository, KnowledgeIndexPort indexPort) {
        return new KnowledgeDeleteTaskHandler(repository, indexPort);
    }
}
