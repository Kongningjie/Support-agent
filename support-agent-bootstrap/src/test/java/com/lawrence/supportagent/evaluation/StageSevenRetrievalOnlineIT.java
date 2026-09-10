package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.knowledge.ElasticsearchKnowledgeIndexAdapter;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import com.lawrence.supportagent.model.DashScopeEmbeddingModelAdapter;
import com.lawrence.supportagent.model.DashScopeRerankModelAdapter;
import com.lawrence.supportagent.retrieval.RetrievalMode;
import com.lawrence.supportagent.retrieval.RetrievalParameters;
import com.lawrence.supportagent.retrieval.RetrievalService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 DashScope Embedding 与 Rerank 限量对比阶段六和阶段七参数。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class StageSevenRetrievalOnlineIT {
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
            "support-agent-stage7-online-elasticsearch", false).withDockerfile(
            Path.of("..", "deploy", "elasticsearch", "Dockerfile").toAbsolutePath());

    @Container
    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(IMAGE)
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));

    /** 用每类前五条共三十条样本验证真实模型质量、阈值与请求量边界。 */
    @Test
    void shouldValidateFinalParametersWithLimitedRealModelCalls() throws Exception {
        Instant startedAt = Instant.now();
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ClasspathRetrievalEvaluationDatasetAdapter datasets =
                new ClasspathRetrievalEvaluationDatasetAdapter(mapper);
        RetrievalEvaluationDatasetSnapshot snapshot =
                datasets.load(EvaluationDatasetKind.OPTIMIZATION_DEVELOPMENT);
        List<String> selectedCaseIds = representativeCaseIds(snapshot.cases());
        assertEquals(30, selectedCaseIds.size());
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String baseUrl = environment("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
        String embeddingName = environment("SUPPORT_AGENT_EMBEDDING_MODEL", "text-embedding-v4");
        String rerankName = environment("SUPPORT_AGENT_RERANK_MODEL", "qwen3-rerank");
        URI endpoint = URI.create("http://" + ELASTICSEARCH.getHost() + ":"
                + ELASTICSEARCH.getMappedPort(9200));
        try (Rest5Client client = Rest5Client.builder(endpoint).build();
             DashScopeEmbeddingModelAdapter embeddings = new DashScopeEmbeddingModelAdapter(
                     apiKey, embeddingName, baseUrl)) {
            DashScopeRerankModelAdapter rerank = new DashScopeRerankModelAdapter(
                    apiKey, rerankName, baseUrl);
            List<IndexedKnowledgeChunk> corpus = corpus(mapper, embeddings);
            RetrievalExperimentCandidateResult before = evaluate(client, mapper, datasets,
                    corpus, embeddings, rerank, RetrievalParameters.stageSixBaseline(),
                    selectedCaseIds, "stage6-real");
            RetrievalExperimentCandidateResult after = evaluate(client, mapper, datasets,
                    corpus, embeddings, rerank, RetrievalParameters.baseline(),
                    selectedCaseIds, "stage7-real");
            assertNoQualityRegression(before, after);
            assertEquals(0, after.diagnostics().falseNoHitCount(),
                    "真实模型不得把有知识样本误拒绝为无知识");
            assertEquals(0, after.diagnostics().falseGroundedCount(),
                    "真实模型不得把无知识样本误接受为有知识");
            assertEquals(1.0, after.metrics().exactTermRecall(),
                    "真实模型精确词召回必须完整");
            writeReport(mapper, new OnlineComparisonReport("1.0", startedAt, Instant.now(),
                    snapshot.version(), snapshot.contentSha256(), embeddingName, rerankName,
                    selectedCaseIds.size(), before, after));
        }
    }

    /** 从六个冻结分类各取数据集顺序靠前的五条，避免为结果人工挑样本。 */
    private List<String> representativeCaseIds(List<RetrievalEvaluationCase> cases) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> selected = new ArrayList<>();
        for (RetrievalEvaluationCase testCase : cases) {
            int count = counts.getOrDefault(testCase.category(), 0);
            if (count < 5) {
                selected.add(testCase.caseId());
                counts.put(testCase.category(), count + 1);
            }
        }
        assertEquals(Set.of("DIRECT", "NOISY", "EXACT", "MULTITURN", "NOHIT", "CONFLICT"),
                counts.keySet());
        assertTrue(counts.values().stream().allMatch(value -> value == 5));
        return List.copyOf(selected);
    }

    /** 使用真实模型和独立索引运行一个参数快照的三十条评测。 */
    private RetrievalExperimentCandidateResult evaluate(
            Rest5Client client, ObjectMapper mapper,
            ClasspathRetrievalEvaluationDatasetAdapter datasets,
            List<IndexedKnowledgeChunk> corpus, DashScopeEmbeddingModelAdapter embeddings,
            DashScopeRerankModelAdapter rerank, RetrievalParameters parameters,
            List<String> selectedCaseIds, String candidateId) throws Exception {
        ElasticsearchKnowledgeIndexAdapter index = new ElasticsearchKnowledgeIndexAdapter(
                client, mapper, "support_knowledge_" + candidateId.replace('-', '_'),
                "support_knowledge_current_" + candidateId.replace('-', '_'),
                "", "", parameters);
        index.ensureReady();
        index.indexChunks(corpus);
        LinkedBlockingQueue<RetrievalEvaluationRun> reports = new LinkedBlockingQueue<>();
        try (RetrievalService retrieval = new RetrievalService(index,
                requested -> Set.copyOf(requested), embeddings, rerank, parameters);
             RetrievalEvaluationService service = new RetrievalEvaluationService(datasets,
                     reports::add, retrieval, new RetrievalMetricsCalculator(), UUID::randomUUID,
                     Instant::now, snapshot -> new RetrievalEvaluationContext("1.1", snapshot.kind(),
                     snapshot.version(), snapshot.contentSha256(), "test",
                     Map.of("embedding", environment("SUPPORT_AGENT_EMBEDDING_MODEL",
                                     "text-embedding-v4"),
                             "rerank", environment("SUPPORT_AGENT_RERANK_MODEL",
                                     "qwen3-rerank")), parameters.asReportMap()))) {
            service.start(EvaluationDatasetKind.OPTIMIZATION_DEVELOPMENT,
                    RetrievalMode.HYBRID_RERANK, selectedCaseIds);
            RetrievalEvaluationRun run = reports.poll(300, TimeUnit.SECONDS);
            assertNotNull(run, "真实模型评测未在五分钟内完成：" + candidateId);
            assertEquals(RetrievalEvaluationRun.Status.SUCCEEDED, run.status(),
                    "真实模型评测运行失败：" + candidateId);
            return new RetrievalExperimentCandidateResult(candidateId, parameters.asReportMap(),
                    run.metrics(), run.diagnostics(), run.retrievalLatency(), run.results());
        }
    }

    /** 使用真实 Embedding 批量构造不改变正文的十五个语料分块。 */
    private List<IndexedKnowledgeChunk> corpus(ObjectMapper mapper,
                                               DashScopeEmbeddingModelAdapter embeddings)
            throws Exception {
        List<CorpusRow> rows = new ArrayList<>();
        try (var stream = java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                "/evaluation/retrieval-corpus.jsonl"))) {
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList()) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                rows.add(new CorpusRow(node.path("sourceId").asText(),
                        node.path("title").asText(), node.path("content").asText()));
            }
        }
        List<List<Double>> vectors = embeddings.embedDocuments(rows.stream()
                .map(value -> value.title() + "\n" + value.content()).toList(), () -> { });
        ExactTermExtractor extractor = new ExactTermExtractor();
        List<IndexedKnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            CorpusRow row = rows.get(index);
            String[] parts = row.sourceId().split(":", 2);
            long sourceId = Long.parseLong(parts[1]);
            chunks.add(new IndexedKnowledgeChunk(parts[0] + ":" + sourceId + ":1:0",
                    parts[0], sourceId, 1, 0, row.title(), row.title(), row.content(),
                    extractor.extract(row.content()), "stage7-online-hash-" + index,
                    vectors.get(index), Instant.EPOCH, Instant.EPOCH,
                    "stage7-online-one-chunk-v1", ExactTermExtractor.VERSION));
        }
        return List.copyOf(chunks);
    }

    /** 断言最终候选的五项质量指标均不低于阶段六真实模型对照。 */
    private void assertNoQualityRegression(RetrievalExperimentCandidateResult before,
                                           RetrievalExperimentCandidateResult after) {
        assertTrue(after.metrics().recallAt5() >= before.metrics().recallAt5());
        assertTrue(after.metrics().mrrAt10() >= before.metrics().mrrAt10());
        assertTrue(after.metrics().ndcgAt5() >= before.metrics().ndcgAt5());
        assertTrue(after.metrics().noHitAccuracy() >= before.metrics().noHitAccuracy());
        assertTrue(after.metrics().exactTermRecall() >= before.metrics().exactTermRecall());
    }

    /** 把不含密钥、问题和正文的在线对照结果写入构建产物目录。 */
    private void writeReport(ObjectMapper mapper, OnlineComparisonReport report) throws Exception {
        Path directory = Path.of("target", "stage-7-online");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("comparison-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    /** 读取非空环境配置，未提供时使用明确默认值。 */
    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 保存从固定 JSONL 读取的单条非敏感语料。 */
    private record CorpusRow(String sourceId, String title, String content) { }

    /** 保存限量在线对照的复现信息和两组结果。 */
    private record OnlineComparisonReport(
            String schemaVersion, Instant startedAt, Instant finishedAt,
            String datasetVersion, String datasetSha256, String embeddingModel,
            String rerankModel, int selectedCaseCount,
            RetrievalExperimentCandidateResult before,
            RetrievalExperimentCandidateResult after) { }
}
