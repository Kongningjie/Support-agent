package com.lawrence.supportagent.config;

import com.lawrence.supportagent.agent.model.DashScopeChatModelAdapter;
import com.lawrence.supportagent.agent.model.DashScopeConversationSummaryAdapter;
import com.lawrence.supportagent.agent.model.DashScopeIntentRecognitionAdapter;
import com.lawrence.supportagent.agent.model.ModelGenerationSettings;
import com.lawrence.supportagent.agent.model.UnavailableChatModelAdapter;
import com.lawrence.supportagent.agent.model.UnavailableConversationSummaryAdapter;
import com.lawrence.supportagent.agent.model.UnavailableIntentRecognitionAdapter;
import com.lawrence.supportagent.agent.model.DashScopeUserMemoryCandidateAdapter;
import com.lawrence.supportagent.agent.model.UnavailableUserMemoryCandidateAdapter;
import com.lawrence.supportagent.chat.AnswerValidator;
import com.lawrence.supportagent.chat.ChatUseCase;
import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import com.lawrence.supportagent.chat.ConversationContextService;
import com.lawrence.supportagent.chat.ConversationLifecycleUseCase;
import com.lawrence.supportagent.chat.ConversationMemorySettings;
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
import com.lawrence.supportagent.model.ConversationSummaryPort;
import com.lawrence.supportagent.model.DashScopeRerankModelAdapter;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.IntentRecognitionPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.memory.UserMemoryCandidateService;
import com.lawrence.supportagent.memory.UserMemoryCandidateSettings;
import com.lawrence.supportagent.memory.UserMemoryCleanupService;
import com.lawrence.supportagent.memory.UserMemoryContentPolicy;
import com.lawrence.supportagent.memory.UserMemoryContextService;
import com.lawrence.supportagent.memory.UserMemoryUseCase;
import com.lawrence.supportagent.memory.port.UserMemoryCandidatePort;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import com.lawrence.supportagent.memory.port.UserMemoryRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.persistence.mapper.AgentAuditMapper;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.RetrievalAnalysisProfile;
import com.lawrence.supportagent.retrieval.RetrievalParameters;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import com.lawrence.supportagent.security.DeterministicPromptSecurityPolicy;
import com.lawrence.supportagent.security.LlmSecuritySettings;
import com.lawrence.supportagent.security.DeterministicModelOutputSecurityPolicy;
import com.lawrence.supportagent.security.ModelOutputSecurityPolicy;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.security.PromptSecurityPolicy;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 装配阶段四聊天、混合检索、模型适配和会话审计能力。 */
@Configuration
public class ChatConfiguration {
    /** 创建阶段 15 冻结的输入阻断和高风险上下文排除设置。 */
    @Bean public LlmSecuritySettings llmSecuritySettings(
            @Value("${support-agent.security.llm.enabled:true}") boolean enabled,
            @Value("${support-agent.security.llm.block-high-confidence-input:true}") boolean blockInput,
            @Value("${support-agent.security.llm.exclude-high-risk-context:true}") boolean excludeContext,
            @Value("${support-agent.security.llm.prompt-canary-enabled:true}") boolean promptCanaryEnabled,
            @Value("${support-agent.security.llm.maximum-regenerations:1}") int maximumRegenerations,
            Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && (!enabled || !blockInput || !excludeContext || !promptCanaryEnabled
                || maximumRegenerations != 1)) {
            throw new IllegalArgumentException("生产环境不得关闭 LLM 安全策略或修改输出重生成次数");
        }
        return new LlmSecuritySettings(enabled, blockInput, excludeContext,
                promptCanaryEnabled, maximumRegenerations);
    }
    /** 创建不依赖外部模型或服务的确定性 Prompt 注入策略。 */
    @Bean public PromptSecurityPolicy promptSecurityPolicy(LlmSecuritySettings settings) {
        return new DeterministicPromptSecurityPolicy(settings);
    }
    /** 创建不携带正文和高基数标签的二期优化遥测端口。 */
    @Bean public OptimizationTelemetryPort optimizationTelemetryPort(MeterRegistry registry) {
        return new MicrometerOptimizationTelemetryAdapter(registry);
    }
    /** 创建不携带正文和业务标识的长期记忆候选遥测端口。 */
    @Bean public MemoryCandidateTelemetryPort memoryCandidateTelemetryPort(MeterRegistry registry) {
        return new MicrometerMemoryCandidateTelemetryAdapter(registry);
    }
    /** 创建独立意图模型端口。 */
    @Bean public IntentRecognitionPort intentRecognitionPort(SupportAgentProperties properties) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) return new UnavailableIntentRecognitionAdapter();
        return new DashScopeIntentRecognitionAdapter(config.apiKey(), config.intentModel(), config.baseUrl(),
                generationSettings(config));
    }
    /** 创建内部流式 Chat 与受控工单 Agent 端口。 */
    @Bean public ChatModelPort chatModelPort(SupportAgentProperties properties,
                                              ObjectMapper objectMapper,
                                              OptimizationTelemetryPort telemetry) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) return new UnavailableChatModelAdapter();
        return new DashScopeChatModelAdapter(config.apiKey(), config.chatModel(),
                config.baseUrl(), objectMapper, telemetry, generationSettings(config),
                config.groundedPromptVariant());
    }
    /** 创建与普通 Chat 端口隔离的单会话结构化摘要模型。 */
    @Bean public ConversationSummaryPort conversationSummaryPort(
            SupportAgentProperties properties, ObjectMapper objectMapper,
            OptimizationTelemetryPort telemetry) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            return new UnavailableConversationSummaryAdapter();
        }
        return new DashScopeConversationSummaryAdapter(config.apiKey(), config.summaryModel(),
                config.baseUrl(), objectMapper, config.summaryTimeout(),
                config.summaryMaxOutputTokens(), telemetry, ignored -> { });
    }
    /** 创建与 Chat 和会话摘要隔离的长期记忆候选模型端口。 */
    @Bean public UserMemoryCandidatePort userMemoryCandidatePort(
            SupportAgentProperties properties, ObjectMapper objectMapper) {
        var config = properties.dashscope();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            return new UnavailableUserMemoryCandidateAdapter();
        }
        return new DashScopeUserMemoryCandidateAdapter(config.apiKey(), config.memoryModel(),
                config.baseUrl(), objectMapper, config.memoryTimeout(),
                config.memoryMaxOutputTokens());
    }
    /** 创建独立 Rerank 模型端口。 */
    @Bean public RerankModelPort rerankModelPort(SupportAgentProperties properties,
                                                  OptimizationTelemetryPort telemetry) {
        var config = properties.dashscope();
        return new DashScopeRerankModelAdapter(config.apiKey(), config.rerankModel(),
                config.baseUrl(), telemetry, config.rerankTimeout(),
                config.retryMaxAttempts(), config.retryInitialDelay());
    }
    /** 创建会话和建议 Redis 适配器。 */
    @Bean public ConversationStorePort conversationStorePort(
            StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${support-agent.conversation.ttl:7d}") Duration conversationTtl,
            @Value("${support-agent.conversation.suggestion-ttl:24h}") Duration suggestionTtl,
            @Value("${support-agent.conversation.run-lease:3m}") Duration runLease,
            @Value("${support-agent.conversation.suggestion-lease:3m}") Duration suggestionLease) {
        return new RedisConversationStoreAdapter(redis, mapper, conversationTtl,
                suggestionTtl, runLease, suggestionLease);
    }
    /** 创建阶段 10 冻结的不可变 Token 预算与滚动摘要阈值。 */
    @Bean public ConversationMemorySettings conversationMemorySettings(
            @Value("${support-agent.conversation.input-budget-tokens:24000}") int inputBudget,
            @Value("${support-agent.conversation.output-reserve-tokens:1200}") int outputReserve,
            @Value("${support-agent.conversation.safety-margin-tokens:1024}") int safetyMargin,
            @Value("${support-agent.conversation.recent-full-turns:6}") int recentTurns,
            @Value("${support-agent.conversation.soft-trigger-turns:12}") int softTurns,
            @Value("${support-agent.conversation.soft-trigger-memory-tokens:6000}") int softTokens,
            @Value("${support-agent.conversation.hard-trigger-turns:20}") int hardTurns) {
        return new ConversationMemorySettings(inputBudget, outputReserve, safetyMargin,
                recentTurns, softTurns, softTokens, hardTurns);
    }
    /** 创建应用关闭时可统一回收的虚拟线程摘要执行器。 */
    @Bean(destroyMethod = "close") public ExecutorService conversationSummaryExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
    /** 创建独立于摘要任务的虚拟线程长期记忆候选执行器。 */
    @Bean(destroyMethod = "close") public ExecutorService userMemoryCandidateExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
    /** 创建长期记忆正文安全策略。 */
    @Bean public UserMemoryContentPolicy userMemoryContentPolicy() {
        return new UserMemoryContentPolicy();
    }
    /** 创建候选保留期、并发上限和清理批量的冻结配置。 */
    @Bean public UserMemoryCandidateSettings userMemoryCandidateSettings(
            @Value("${support-agent.memory.candidate-retention:30d}") Duration retention,
            @Value("${support-agent.memory.candidate-global-concurrency:4}") int globalConcurrency,
            @Value("${support-agent.memory.candidate-per-user-concurrency:1}") int perUserConcurrency,
            @Value("${support-agent.memory.cleanup-batch-size:500}") int cleanupBatchSize) {
        return new UserMemoryCandidateSettings(retention, globalConcurrency,
                perUserConcurrency, cleanupBatchSize);
    }
    /** 创建预算内长期记忆选择服务。 */
    @Bean public UserMemoryContextService userMemoryContextService(
            UserMemoryRepository repository, TimeProvider time,
            @Value("${support-agent.memory.input-budget-tokens:1000}") int tokenBudget) {
        return new UserMemoryContextService(repository, new ConservativeTokenEstimator(),
                time, tokenBudget);
    }
    /** 创建聊天成功后的尽力长期记忆候选服务。 */
    @Bean public UserMemoryCandidateService userMemoryCandidateService(
            UserMemoryRepository repository, UserMemoryCandidatePort model,
            UserMemoryContentPolicy contentPolicy, UuidGenerator ids, TimeProvider time,
            ExecutorService userMemoryCandidateExecutor,
            MemoryCandidateTelemetryPort telemetry, UserMemoryCandidateSettings settings) {
        return new UserMemoryCandidateService(repository, model, contentPolicy, ids, time,
                userMemoryCandidateExecutor, telemetry, settings);
    }
    /** 创建只处理过期待确认候选的后台清理应用服务。 */
    @Bean public UserMemoryCleanupService userMemoryCleanupService(
            UserMemoryRepository repository, MemoryCandidateTelemetryPort telemetry,
            TimeProvider time, UserMemoryCandidateSettings settings) {
        return new UserMemoryCleanupService(repository, telemetry, time, settings);
    }
    /** 创建用户本人长期记忆生命周期用例。 */
    @Bean public UserMemoryUseCase userMemoryUseCase(
            UserMemoryRepository repository, UserMemoryContentPolicy contentPolicy,
            IdempotentExecutor idempotency, TimeProvider time) {
        return new UserMemoryUseCase(repository, contentPolicy, idempotency, time);
    }
    /** 创建独立于模型和 Redis 实现的单会话上下文编排服务。 */
    @Bean public ConversationContextService conversationContextService(
            ConversationStorePort store, ConversationSummaryPort summaryModel,
            ExactTermExtractor exactTerms, DocumentContentPolicy contentPolicy,
            ConversationMemorySettings settings,
            ExecutorService conversationSummaryExecutor, TimeProvider time,
            UserMemoryContextService userMemoryContextService,
            PromptSecurityPolicy promptSecurity) {
        return new ConversationContextService(store, summaryModel, exactTerms, contentPolicy,
                new ConservativeTokenEstimator(), settings, conversationSummaryExecutor, time,
                userMemoryContextService, promptSecurity);
    }
    /** 创建会话列表、详情、重置和删除的生命周期用例。 */
    @Bean public ConversationLifecycleUseCase conversationLifecycleUseCase(
            ConversationStorePort store, TimeProvider time) {
        return new ConversationLifecycleUseCase(store, time);
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
    /** 使用配置文件中的完整字段创建不可变检索参数快照。 */
    @Bean public RetrievalParameters retrievalParameters(
            @Value("${support-agent.retrieval.bm25-top-k:30}") int bm25TopK,
            @Value("${support-agent.retrieval.vector-top-k:30}") int vectorTopK,
            @Value("${support-agent.retrieval.vector-candidates:100}") int vectorCandidates,
            @Value("${support-agent.retrieval.vector-minimum-similarity:0.20}") double similarity,
            @Value("${support-agent.retrieval.rrf-k:20}") int rrfK,
            @Value("${support-agent.retrieval.fusion-top-k:20}") int fusionTopK,
            @Value("${support-agent.retrieval.rerank-top-k:20}") int rerankTopK,
            @Value("${support-agent.retrieval.ranked-top-k:10}") int rankedTopK,
            @Value("${support-agent.retrieval.final-top-k:5}") int finalTopK,
            @Value("${support-agent.retrieval.rerank-grounded-threshold:0.30}") double threshold,
            @Value("${support-agent.retrieval.analysis-profile:ICU_ONLY}")
            RetrievalAnalysisProfile analysisProfile,
            @Value("${support-agent.retrieval.title-weight:3.0}") double titleWeight,
            @Value("${support-agent.retrieval.heading-weight:2.0}") double headingWeight,
            @Value("${support-agent.retrieval.content-weight:1.0}") double contentWeight,
            @Value("${support-agent.retrieval.exact-term-weight:5.0}") double exactTermWeight) {
        return new RetrievalParameters(bm25TopK, vectorTopK, vectorCandidates, similarity,
                rrfK, fusionTopK, rerankTopK, rankedTopK, finalTopK, threshold,
                analysisProfile, titleWeight, headingWeight, contentWeight, exactTermWeight);
    }
    /** 创建混合检索编排器。 */
    @Bean(destroyMethod = "close") public RetrievalService retrievalService(
            ElasticsearchKnowledgeIndexAdapter search, KnowledgeSourceValidityPort validity,
            EmbeddingModelPort embedding, RerankModelPort rerank,
            RetrievalParameters parameters,
            OptimizationTelemetryPort telemetry) {
        return new RetrievalService(search, validity, embedding, rerank, parameters, telemetry);
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
    /** 创建不依赖外部服务的确定性模型输出安全策略。 */
    @Bean public ModelOutputSecurityPolicy modelOutputSecurityPolicy(AnswerValidator validator) {
        return new DeterministicModelOutputSecurityPolicy(validator);
    }
    /** 创建单次随机标记与输出决策的统一安全服务。 */
    @Bean public ModelOutputSecurityService modelOutputSecurityService(
            ModelOutputSecurityPolicy policy, LlmSecuritySettings settings, UuidGenerator ids) {
        return new ModelOutputSecurityService(policy, settings, ids);
    }
    /** 创建阶段四聊天用例。 */
    @Bean public ChatUseCase chatUseCase(IntentRecognitionService intents, RetrievalService retrieval,
                                         ChatModelPort model, TicketQueryUseCase tickets,
                                         ConversationStorePort conversations, AgentAuditPort audits,
                                         AnswerValidator validator, UuidGenerator ids, TimeProvider time,
                                         SupportAgentProperties properties,
                                         RetrievalParameters parameters,
                                         ConversationContextService contextService,
                                         UserMemoryCandidateService memoryCandidates,
                                         OptimizationTelemetryPort telemetry,
                                         PromptSecurityPolicy promptSecurity,
                                         ModelOutputSecurityService outputSecurity,
                                         @Value("${support-agent.conversation.suggestion-ttl:24h}")
                                         Duration suggestionTtl) {
        var config = properties.dashscope();
        return new ChatUseCase(intents, retrieval, model, tickets, conversations, audits, validator,
                ids, time, config.chatModel(), config.embeddingModel(), config.rerankModel(),
                parameters.groundedThreshold(), telemetry, suggestionTtl, contextService,
                memoryCandidates, promptSecurity, outputSecurity);
    }

    /** 将启动模块配置转换为模型适配层不可变生成参数。 */
    private ModelGenerationSettings generationSettings(SupportAgentProperties.DashScope config) {
        return new ModelGenerationSettings(config.chatTimeout(), config.intentTimeout(),
                config.ticketTimeout(), config.resolvedCaseTimeout(), config.chatMaxOutputTokens(),
                config.intentMaxOutputTokens(), config.structuredMaxOutputTokens());
    }
}
