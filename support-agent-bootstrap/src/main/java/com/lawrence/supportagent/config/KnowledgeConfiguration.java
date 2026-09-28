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
import com.lawrence.supportagent.knowledge.KnowledgeIndexRebuildUseCase;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexRebuildPort;
import com.lawrence.supportagent.knowledge.ManagedDocumentCommandUseCase;
import com.lawrence.supportagent.knowledge.ManagedDocumentQueryUseCase;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.model.DashScopeEmbeddingModelAdapter;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseCommandUseCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseDeleteTaskHandler;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseIndexTaskHandler;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseQueryUseCase;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.RetrievalParameters;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.net.URI;
import org.apache.hc.core5.util.Timeout;
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
            ManagedDocumentRepository repository, KnowledgeSpaceRepository spaces,
            KnowledgeSpaceAccessService access) {
        return new ManagedDocumentQueryUseCase(repository, spaces, access);
    }

    /** 创建托管文档命令用例。 */
    @Bean
    public ManagedDocumentCommandUseCase managedDocumentCommandUseCase(
            ManagedDocumentRepository repository, AsyncTaskRepository taskRepository,
            ManagedDocumentQueryUseCase queryUseCase, AsyncTaskCreator taskCreator,
            IdempotentExecutor idempotentExecutor, OperatorProvider operatorProvider,
            TimeProvider timeProvider, DocumentContentPolicy contentPolicy,
            KnowledgeSpaceAccessService access) {
        return new ManagedDocumentCommandUseCase(repository, taskRepository, queryUseCase,
                taskCreator, idempotentExecutor, operatorProvider, timeProvider, contentPolicy,
                access);
    }

    /** 创建不会在启动时访问远端的 DashScope Embedding 独立适配器。 */
    @Bean(destroyMethod = "close")
    public EmbeddingModelPort embeddingModelPort(SupportAgentProperties properties,
                                                  OptimizationTelemetryPort telemetry) {
        SupportAgentProperties.DashScope config = properties.dashscope();
        return new DashScopeEmbeddingModelAdapter(config.apiKey(), config.embeddingModel(),
                config.baseUrl(), telemetry, config.embeddingTimeout(),
                config.retryMaxAttempts(), config.retryInitialDelay());
    }

    /** 创建 Elasticsearch REST5 客户端；索引仍由首个任务惰性检查。 */
    @Bean(destroyMethod = "close")
    public Rest5Client elasticsearchRest5Client(SupportAgentProperties properties) {
        SupportAgentProperties.Elasticsearch config = properties.elasticsearch();
        return Rest5Client.builder(URI.create(config.url()))
                .setConnectionConfigCallback(builder -> builder.setConnectTimeout(
                        Timeout.ofMilliseconds(config.connectTimeout().toMillis())))
                .setRequestConfigCallback(builder -> builder
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                                config.connectionRequestTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(
                                config.responseTimeout().toMillis()))
                        .setHardCancellationEnabled(true))
                .build();
    }

    /** 创建知识索引持久化端口。 */
    @Bean
    public ElasticsearchKnowledgeIndexAdapter knowledgeIndexPort(Rest5Client client, ObjectMapper objectMapper,
                                                                  SupportAgentProperties properties,
                                                                  RetrievalParameters retrievalParameters) {
        SupportAgentProperties.Elasticsearch config = properties.elasticsearch();
        return new ElasticsearchKnowledgeIndexAdapter(client, objectMapper,
                config.knowledgeIndex(), config.knowledgeAlias(),
                config.username(), config.password(), retrievalParameters);
    }

    /** 创建从 MySQL 当前已发布来源重建目标物理索引的管理员用例。 */
    @Bean
    public KnowledgeIndexRebuildUseCase knowledgeIndexRebuildUseCase(
            ManagedDocumentRepository documents, ResolvedCaseRepository cases,
            KnowledgeSpaceRepository spaces, DocumentChunker chunker,
            EmbeddingModelPort embeddings, KnowledgeIndexRebuildPort index,
            TimeProvider time, KnowledgeSpaceTelemetryPort telemetry) {
        return new KnowledgeIndexRebuildUseCase(documents, cases, spaces, chunker,
                embeddings, index, time, telemetry);
    }

    /** 注册托管文档异步索引任务处理器。 */
    @Bean
    public KnowledgeIndexTaskHandler knowledgeIndexTaskHandler(
            ManagedDocumentRepository repository, DocumentContentPolicy contentPolicy,
            DocumentChunker chunker, EmbeddingModelPort embeddingModel,
            KnowledgeIndexPort indexPort, TimeProvider timeProvider,
            KnowledgeSpaceRepository spaces) {
        return new KnowledgeIndexTaskHandler(repository, contentPolicy, chunker,
                embeddingModel, indexPort, timeProvider, spaces);
    }

    /** 注册归档文档的 Elasticsearch 删除任务处理器。 */
    @Bean
    public KnowledgeDeleteTaskHandler knowledgeDeleteTaskHandler(
            ManagedDocumentRepository repository, KnowledgeIndexPort indexPort) {
        return new KnowledgeDeleteTaskHandler(repository, indexPort);
    }

    /** 创建案例人工审核、发布和归档命令用例。 */
    @Bean
    public ResolvedCaseCommandUseCase resolvedCaseCommandUseCase(
            ResolvedCaseRepository repository, ResolvedCaseQueryUseCase queries,
            AsyncTaskCreator taskCreator, IdempotentExecutor idempotency,
            OperatorProvider operators, TimeProvider time, DocumentContentPolicy policy,
            KnowledgeSpaceAccessService access, TicketRepository tickets) {
        return new ResolvedCaseCommandUseCase(repository, queries, taskCreator,
                idempotency, operators, time, policy, access, tickets);
    }

    /** 注册案例异步知识索引处理器。 */
    @Bean
    public ResolvedCaseIndexTaskHandler resolvedCaseIndexTaskHandler(
            ResolvedCaseRepository repository, DocumentContentPolicy policy,
            DocumentChunker chunker, EmbeddingModelPort embeddings,
            KnowledgeIndexPort index, TimeProvider time,
            KnowledgeSpaceRepository spaces) {
        return new ResolvedCaseIndexTaskHandler(repository, policy, chunker,
                embeddings, index, time, spaces);
    }

    /** 注册案例归档后的索引删除处理器。 */
    @Bean
    public ResolvedCaseDeleteTaskHandler resolvedCaseDeleteTaskHandler(
            ResolvedCaseRepository repository, KnowledgeIndexPort index) {
        return new ResolvedCaseDeleteTaskHandler(repository, index);
    }
}
