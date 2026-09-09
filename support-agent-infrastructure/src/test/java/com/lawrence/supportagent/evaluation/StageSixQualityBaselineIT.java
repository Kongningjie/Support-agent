package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.knowledge.ElasticsearchKnowledgeIndexAdapter;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.retrieval.RetrievalMode;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 Elasticsearch 和确定性模型生成四模式阶段六质量基线。 */
@Testcontainers
class StageSixQualityBaselineIT {
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
            "support-agent-stage6-elasticsearch", false).withDockerfile(
            Path.of("..", "deploy", "elasticsearch", "Dockerfile").toAbsolutePath());

    @Container
    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(IMAGE)
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));

    /** 连续运行两次四模式质量评测，断言指标一致并写出统一报告。 */
    @Test
    void shouldProduceRepeatableFourModeQualityBaseline() throws Exception {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        URI endpoint = URI.create("http://" + ELASTICSEARCH.getHost() + ":"
                + ELASTICSEARCH.getMappedPort(9200));
        try (Rest5Client client = Rest5Client.builder(endpoint).build()) {
            ElasticsearchKnowledgeIndexAdapter index = new ElasticsearchKnowledgeIndexAdapter(
                    client, mapper, "support_knowledge_stage6_v1", "support_knowledge_stage6_current",
                    "", "");
            index.ensureReady();
            index.indexChunks(corpus(mapper));
            EmbeddingModelPort embeddings = new DeterministicEmbeddingModel();
            RerankModelPort rerank = new DeterministicRerankModel();
            try (RetrievalService retrieval = new RetrievalService(index, requested -> Set.copyOf(requested),
                    embeddings, rerank, 0.20, 200, 0.35)) {
                ClasspathRetrievalEvaluationDatasetAdapter datasets =
                        new ClasspathRetrievalEvaluationDatasetAdapter(mapper);
                Map<String, QualityResult> first = evaluateAll(datasets, retrieval);
                Map<String, QualityResult> second = evaluateAll(datasets, retrieval);
                first.forEach((key, value) -> assertEquals(value.metrics, second.get(key).metrics,
                        "相同数据、模型和参数的质量指标必须完全一致：" + key));
                writeReport(mapper, new QualityReport("1.0",
                        new WorkingTreeGitCommitResolver(Path.of("..")).resolve(), startedAt,
                        Instant.now(), elapsed(started),
                        Map.of("embedding", "stage6-char-bigram-v1",
                                "rerank", "stage6-char-bigram-v1"),
                        Map.of("bm25TopK", "50", "vectorTopK", "50", "vectorCandidates", "200",
                                "vectorMinimumSimilarity", "0.20", "rrfK", "60",
                                "fusionTopK", "30", "rankedTopK", "10"), first));
            }
        }
    }

    /** 依次执行两套数据集和四种检索模式并收集统一结果。 */
    private Map<String, QualityResult> evaluateAll(ClasspathRetrievalEvaluationDatasetAdapter datasets,
                                                   RetrievalService retrieval) throws Exception {
        Map<String, QualityResult> values = new LinkedHashMap<>();
        for (EvaluationDatasetKind kind : EvaluationDatasetKind.values()) {
            for (RetrievalMode mode : RetrievalMode.values()) {
                RetrievalEvaluationDatasetSnapshot snapshot = datasets.load(kind);
                LinkedBlockingQueue<RetrievalEvaluationRun> reports = new LinkedBlockingQueue<>();
                try (RetrievalEvaluationService service = new RetrievalEvaluationService(datasets,
                        reports::add, retrieval, new RetrievalMetricsCalculator(), UUID::randomUUID,
                        Instant::now, data -> new RetrievalEvaluationContext("1.0", data.kind(),
                        data.version(), data.contentSha256(), "test", Map.of(
                        "embedding", "stage6-deterministic", "rerank", "stage6-deterministic"),
                        Map.of("mode", mode.name())))) {
                    service.start(kind, mode, List.of());
                    RetrievalEvaluationRun run = reports.poll(120, TimeUnit.SECONDS);
                    assertNotNull(run, "质量评测未在超时前完成：" + kind + "/" + mode);
                    values.put(kind + "/" + mode, new QualityResult(run.metrics(),
                            run.retrievalLatency(), snapshot.version(), snapshot.contentSha256()));
                }
            }
        }
        return Map.copyOf(values);
    }

    /** 从固定语料创建十五个单分块索引文档。 */
    private List<IndexedKnowledgeChunk> corpus(ObjectMapper mapper) throws Exception {
        byte[] bytes;
        try (var stream = java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                "/evaluation/retrieval-corpus.jsonl"))) {
            bytes = stream.readAllBytes();
        }
        List<IndexedKnowledgeChunk> chunks = new ArrayList<>();
        ExactTermExtractor extractor = new ExactTermExtractor();
        int sequence = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String legacyId = node.path("sourceId").asText();
            String[] parts = legacyId.split(":", 2);
            long sourceId = Long.parseLong(parts[1]);
            String title = node.path("title").asText();
            String content = node.path("content").asText();
            String chunkId = parts[0] + ":" + sourceId + ":1:0";
            chunks.add(new IndexedKnowledgeChunk(chunkId, parts[0], sourceId, 1, 0, title,
                    title, content, extractor.extract(content), "stage6-hash-" + sequence++,
                    DeterministicVector.vector(title + "\n" + content), Instant.EPOCH,
                    Instant.EPOCH, "stage6-one-chunk-v1", ExactTermExtractor.VERSION));
        }
        return List.copyOf(chunks);
    }

    /** 写出包含两套数据和四模式指标的不可变 JSON 报告。 */
    private void writeReport(ObjectMapper mapper, QualityReport report) throws Exception {
        Path directory = Path.of("target", "stage-6-baseline");
        Files.createDirectories(directory);
        Path output = directory.resolve("quality-" + UUID.randomUUID() + ".json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** 将单调时钟纳秒差转换为非负毫秒。 */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 使用字符二元组哈希生成固定 1024 维归一化向量。 */
    private static final class DeterministicVector {
        /** 把文本映射为不依赖模型或网络的稠密向量。 */
        private static List<Double> vector(String text) {
            double[] values = new double[1024];
            String normalized = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
            for (int index = 0; index < normalized.length() - 1; index++) {
                String pair = normalized.substring(index, index + 2);
                values[Math.floorMod(pair.hashCode(), values.length)] += 1;
            }
            double norm = Math.sqrt(java.util.Arrays.stream(values).map(value -> value * value).sum());
            List<Double> result = new ArrayList<>(values.length);
            for (double value : values) result.add(norm == 0 ? 0 : value / norm);
            return List.copyOf(result);
        }
    }

    /** 为文档和查询提供相同确定性向量算法。 */
    private static final class DeterministicEmbeddingModel implements EmbeddingModelPort {
        /** {@inheritDoc} */
        @Override public List<List<Double>> embedDocuments(List<String> documents, Runnable heartbeat) {
            return documents.stream().map(DeterministicVector::vector).toList();
        }
        /** {@inheritDoc} */
        @Override public List<Double> embedQuery(String query) { return DeterministicVector.vector(query); }
    }

    /** 使用查询与候选字符向量余弦相似度执行确定性重排。 */
    private static final class DeterministicRerankModel implements RerankModelPort {
        /** {@inheritDoc} */
        @Override public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
            List<Double> queryVector = DeterministicVector.vector(query);
            return documents.stream().map(document -> new RerankScore(document.chunkId(),
                    cosine(queryVector, DeterministicVector.vector(document.content())))).toList();
        }
        /** 计算两个同维归一化向量的零到一余弦值。 */
        private double cosine(List<Double> left, List<Double> right) {
            double score = 0;
            for (int index = 0; index < left.size(); index++) score += left.get(index) * right.get(index);
            return Math.max(0, Math.min(1, score));
        }
    }

    /** 单个数据集和模式的质量及耗时结果。 */
    private record QualityResult(RetrievalEvaluationMetrics metrics, LatencySummary retrievalLatency,
                                 String datasetVersion, String datasetSha256) { }

    /** 阶段六统一四模式质量报告。 */
    private record QualityReport(String schemaVersion, String gitCommit, Instant startedAt,
                                 Instant finishedAt, long durationMs,
                                 Map<String, String> modelNames,
                                 Map<String, String> retrievalParameters,
                                 Map<String, QualityResult> results) { }
}
