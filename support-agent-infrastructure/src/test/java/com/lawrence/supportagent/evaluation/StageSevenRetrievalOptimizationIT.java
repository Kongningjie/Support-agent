package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.lawrence.supportagent.knowledge.ElasticsearchKnowledgeIndexAdapter;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.IndexedKnowledgeChunk;
import com.lawrence.supportagent.model.EmbeddingModelPort;
import com.lawrence.supportagent.model.RerankModelPort;
import com.lawrence.supportagent.retrieval.RetrievalAnalysisProfile;
import com.lawrence.supportagent.retrieval.RetrievalMode;
import com.lawrence.supportagent.retrieval.RetrievalParameters;
import com.lawrence.supportagent.retrieval.RetrievalService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用固定中文数据和确定性模型执行阶段七分组参数实验及锁定回归。 */
@Testcontainers
class StageSevenRetrievalOptimizationIT {
    private static final double RECALL_FLOOR = 0.933333;
    private static final double MRR_FLOOR = 0.902469;
    private static final double NDCG_FLOOR = 0.908729;
    private static final double EPSILON = 0.000001;
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
            "support-agent-stage7-elasticsearch", false).withDockerfile(
            Path.of("..", "deploy", "elasticsearch", "Dockerfile").toAbsolutePath());

    @Container
    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(IMAGE)
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));

    private final AtomicInteger indexSequence = new AtomicInteger();

    /** 执行分析器、召回、融合、字段权重和全局阈值实验，并验证最终结果可重复。 */
    @Test
    void shouldSelectReproducibleParametersWithoutWeakeningQualityGates() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        URI endpoint = URI.create("http://" + ELASTICSEARCH.getHost() + ":"
                + ELASTICSEARCH.getMappedPort(9200));
        ClasspathRetrievalEvaluationDatasetAdapter datasets =
                new ClasspathRetrievalEvaluationDatasetAdapter(mapper);
        RetrievalEvaluationDatasetSnapshot development =
                datasets.load(EvaluationDatasetKind.OPTIMIZATION_DEVELOPMENT);
        TargetRetrievalExperimentReportAdapter reportWriter =
                new TargetRetrievalExperimentReportAdapter(mapper);
        try (Rest5Client client = Rest5Client.builder(endpoint).build()) {
            List<IndexedKnowledgeChunk> corpus = corpus(mapper);
            RetrievalParameters current = RetrievalParameters.stageSixBaseline();
            current = runAnalyzerGroup(client, mapper, datasets, development, corpus,
                    current, reportWriter);
            current = runGroup(client, mapper, datasets, development, corpus, current,
                    recallCandidates(current), reportWriter);
            current = runGroup(client, mapper, datasets, development, corpus, current,
                    fusionCandidates(current), reportWriter);
            current = runGroup(client, mapper, datasets, development, corpus, current,
                    fieldWeightCandidates(current), reportWriter);
            current = runThresholdGroup(client, mapper, datasets, development, corpus,
                    current, reportWriter);
            assertEquals(RetrievalParameters.baseline(), current,
                    "分组实验选择结果必须与已冻结生产默认参数一致");

            RetrievalExperimentCandidateResult first = evaluate(client, mapper, datasets,
                    EvaluationDatasetKind.LOCKED_REGRESSION, corpus, current, "locked-first");
            RetrievalExperimentCandidateResult second = evaluate(client, mapper, datasets,
                    EvaluationDatasetKind.LOCKED_REGRESSION, corpus, current, "locked-second");
            assertEquals(first.metrics(), second.metrics(), "锁定回归集两次指标必须完全一致");
            assertEquals(caseSemantics(first), caseSemantics(second),
                    "锁定回归集两次逐条排名和可靠性判断必须完全一致");
            assertQualityGates(first);
            writeLockedReport(reportWriter, datasets.load(EvaluationDatasetKind.LOCKED_REGRESSION),
                    current, first, second);
        }
    }

    /** 对比 ICU 主字段和 ICU+CJK 辅助字段，仅在明确提升时选择 CJK。 */
    private RetrievalParameters runAnalyzerGroup(Rest5Client client, ObjectMapper mapper,
                                                  ClasspathRetrievalEvaluationDatasetAdapter datasets,
                                                  RetrievalEvaluationDatasetSnapshot snapshot,
                                                  List<IndexedKnowledgeChunk> corpus,
                                                  RetrievalParameters baseline,
                                                  TargetRetrievalExperimentReportAdapter writer)
            throws Exception {
        List<ParameterCandidate> candidates = List.of(
                candidate("analyzer-icu", RetrievalExperimentGroup.ANALYZER,
                        "仅使用既有 ICU 分析器", baseline.withAnalysisProfile(
                                RetrievalAnalysisProfile.ICU_ONLY), "analysisProfile"),
                candidate("analyzer-icu-cjk", RetrievalExperimentGroup.ANALYZER,
                        "在 ICU 主字段之外增加低权重内置 CJK 辅助字段",
                        baseline.withAnalysisProfile(RetrievalAnalysisProfile.ICU_WITH_CJK),
                        "analysisProfile"));
        GroupRun run = executeGroup(client, mapper, datasets, snapshot, corpus, baseline,
                candidates, writer);
        RetrievalExperimentCandidateResult icu = run.results().get(0);
        RetrievalExperimentCandidateResult cjk = run.results().get(1);
        return clearlyImproves(cjk, icu) ? candidates.get(1).parameters() : baseline;
    }

    /** 执行普通变量组并按冻结的质量优先顺序选择最佳候选。 */
    private RetrievalParameters runGroup(Rest5Client client, ObjectMapper mapper,
                                         ClasspathRetrievalEvaluationDatasetAdapter datasets,
                                         RetrievalEvaluationDatasetSnapshot snapshot,
                                         List<IndexedKnowledgeChunk> corpus,
                                         RetrievalParameters baseline,
                                         List<ParameterCandidate> candidates,
                                         TargetRetrievalExperimentReportAdapter writer)
            throws Exception {
        GroupRun run = executeGroup(client, mapper, datasets, snapshot, corpus, baseline,
                candidates, writer);
        RetrievalExperimentCandidateResult best = selectBest(run.results());
        return candidates.stream().filter(value -> value.id().equals(best.candidateId()))
                .findFirst().orElseThrow().parameters();
    }

    /** 扫描单一全局阈值，只从同时满足可靠性与质量硬门槛的候选中选择。 */
    private RetrievalParameters runThresholdGroup(
            Rest5Client client, ObjectMapper mapper,
            ClasspathRetrievalEvaluationDatasetAdapter datasets,
            RetrievalEvaluationDatasetSnapshot snapshot, List<IndexedKnowledgeChunk> corpus,
            RetrievalParameters baseline, TargetRetrievalExperimentReportAdapter writer)
            throws Exception {
        List<ParameterCandidate> candidates = new ArrayList<>();
        for (int step = 30; step <= 70; step += 5) {
            double threshold = step / 100.0;
            candidates.add(candidate("threshold-" + step,
                    RetrievalExperimentGroup.GROUNDED_THRESHOLD,
                    "全局可靠知识阈值 " + threshold,
                    baseline.withGroundedThreshold(threshold), "rerankGroundedThreshold"));
        }
        GroupRun run = executeGroup(client, mapper, datasets, snapshot, corpus, baseline,
                List.copyOf(candidates), writer);
        RetrievalExperimentCandidateResult best = run.results().stream()
                .filter(this::passesReliabilityGates).max(qualityComparator()).orElseThrow(() ->
                        new AssertionError("0.30～0.70 范围内不存在满足全部硬门槛的单一全局阈值"));
        return candidates.stream().filter(value -> value.id().equals(best.candidateId()))
                .findFirst().orElseThrow().parameters();
    }

    /** 执行一个完整变量组、写出不可变报告并返回同序候选结果。 */
    private GroupRun executeGroup(Rest5Client client, ObjectMapper mapper,
                                  ClasspathRetrievalEvaluationDatasetAdapter datasets,
                                  RetrievalEvaluationDatasetSnapshot snapshot,
                                  List<IndexedKnowledgeChunk> corpus,
                                  RetrievalParameters baseline,
                                  List<ParameterCandidate> candidates,
                                  TargetRetrievalExperimentReportAdapter writer) throws Exception {
        Instant startedAt = Instant.now();
        List<RetrievalExperimentCandidate> matrixCandidates = candidates.stream()
                .map(ParameterCandidate::matrixCandidate).toList();
        RetrievalExperimentMatrix matrix = new RetrievalExperimentMatrix("1.0",
                baseline.asReportMap(), matrixCandidates);
        List<RetrievalExperimentCandidateResult> results = new ArrayList<>();
        for (ParameterCandidate candidate : candidates) {
            results.add(evaluate(client, mapper, datasets,
                    EvaluationDatasetKind.OPTIMIZATION_DEVELOPMENT, corpus,
                    candidate.parameters(), candidate.id()));
        }
        RetrievalExperimentReport report = new RetrievalExperimentReport(UUID.randomUUID(),
                "1.0", new WorkingTreeGitCommitResolver(Path.of("..")).resolve(),
                snapshot.version(), snapshot.contentSha256(), startedAt, Instant.now(), matrix,
                Map.of("embedding", "stage7-char-bigram-v1",
                        "rerank", "stage7-calibrated-char-bigram-v1"), List.copyOf(results));
        writer.write(report);
        return new GroupRun(List.copyOf(results));
    }

    /** 使用候选参数建立独立索引并运行一次指定数据集的 HYBRID_RERANK 评测。 */
    private RetrievalExperimentCandidateResult evaluate(
            Rest5Client client, ObjectMapper mapper,
            ClasspathRetrievalEvaluationDatasetAdapter datasets, EvaluationDatasetKind kind,
            List<IndexedKnowledgeChunk> corpus, RetrievalParameters parameters,
            String candidateId) throws Exception {
        int sequence = indexSequence.incrementAndGet();
        ElasticsearchKnowledgeIndexAdapter index = new ElasticsearchKnowledgeIndexAdapter(
                client, mapper, "support_knowledge_stage7_" + sequence,
                "support_knowledge_stage7_current_" + sequence, "", "", parameters);
        index.ensureReady();
        index.indexChunks(corpus);
        LinkedBlockingQueue<RetrievalEvaluationRun> reports = new LinkedBlockingQueue<>();
        try (RetrievalService retrieval = new RetrievalService(index, requested -> Set.copyOf(requested),
                new DeterministicEmbeddingModel(), new DeterministicRerankModel(), parameters);
             RetrievalEvaluationService service = new RetrievalEvaluationService(datasets,
                     reports::add, retrieval, new RetrievalMetricsCalculator(), UUID::randomUUID,
                     Instant::now, snapshot -> new RetrievalEvaluationContext("1.1", snapshot.kind(),
                     snapshot.version(), snapshot.contentSha256(), "test",
                     Map.of("embedding", "stage7-char-bigram-v1",
                             "rerank", "stage7-calibrated-char-bigram-v1"),
                             parameters.asReportMap()))) {
            service.start(kind, RetrievalMode.HYBRID_RERANK, List.of());
            RetrievalEvaluationRun run = reports.poll(180, TimeUnit.SECONDS);
            assertNotNull(run, "阶段七候选未在超时前完成：" + candidateId);
            assertEquals(RetrievalEvaluationRun.Status.SUCCEEDED, run.status(),
                    "阶段七候选运行失败：" + candidateId);
            return new RetrievalExperimentCandidateResult(candidateId, parameters.asReportMap(),
                    run.metrics(), run.diagnostics(), run.retrievalLatency(), run.results());
        }
    }

    /** 创建召回宽度候选，保持向量 Top K 不大于候选池。 */
    private List<ParameterCandidate> recallCandidates(RetrievalParameters baseline) {
        return List.of(
                candidate("recall-30-100", RetrievalExperimentGroup.RECALL_BREADTH,
                        "BM25 与向量各取 30，向量候选池 100",
                        baseline.withRecallBreadth(30, 30, 100),
                        "bm25TopK", "vectorTopK", "vectorCandidates",
                        "vectorMinimumSimilarity"),
                candidate("recall-50-200", RetrievalExperimentGroup.RECALL_BREADTH,
                        "保持阶段六召回宽度", baseline.withRecallBreadth(50, 50, 200),
                        "bm25TopK", "vectorTopK", "vectorCandidates",
                        "vectorMinimumSimilarity"),
                candidate("recall-80-300", RetrievalExperimentGroup.RECALL_BREADTH,
                        "BM25 与向量各取 80，向量候选池 300",
                        baseline.withRecallBreadth(80, 80, 300),
                        "bm25TopK", "vectorTopK", "vectorCandidates",
                        "vectorMinimumSimilarity"));
    }

    /** 创建覆盖冻结 RRF 和融合宽度范围的有限候选。 */
    private List<ParameterCandidate> fusionCandidates(RetrievalParameters baseline) {
        return List.of(
                fusionCandidate("fusion-20-20", "RRF 20、融合和重排各 20", baseline, 20, 20),
                fusionCandidate("fusion-40-30", "RRF 40、融合和重排各 30", baseline, 40, 30),
                fusionCandidate("fusion-60-30", "保持阶段六融合参数", baseline, 60, 30),
                fusionCandidate("fusion-80-30", "RRF 80、融合和重排各 30", baseline, 80, 30),
                fusionCandidate("fusion-60-40", "RRF 60、融合和重排各 40", baseline, 60, 40));
    }

    /** 创建一个融合参数候选。 */
    private ParameterCandidate fusionCandidate(String id, String description,
                                               RetrievalParameters baseline, int rrfK, int topK) {
        return candidate(id, RetrievalExperimentGroup.FUSION, description,
                baseline.withFusion(rrfK, topK, topK), "rrfK", "fusionTopK", "rerankTopK");
    }

    /** 创建少量、可解释且不形成极端放大的字段权重候选。 */
    private List<ParameterCandidate> fieldWeightCandidates(RetrievalParameters baseline) {
        return List.of(
                candidate("weights-baseline", RetrievalExperimentGroup.FIELD_WEIGHT,
                        "保持标题 3、路径 2、正文 1、精确词 5",
                        baseline.withFieldWeights(3, 2, 1, 5),
                        "titleWeight", "headingWeight", "contentWeight", "exactTermWeight"),
                candidate("weights-exact-8", RetrievalExperimentGroup.FIELD_WEIGHT,
                        "仅将精确技术词权重有限提高到 8",
                        baseline.withFieldWeights(3, 2, 1, 8),
                        "titleWeight", "headingWeight", "contentWeight", "exactTermWeight"),
                candidate("weights-balanced", RetrievalExperimentGroup.FIELD_WEIGHT,
                        "收窄标题路径差异并保持精确词保护",
                        baseline.withFieldWeights(2.5, 1.5, 1, 8),
                        "titleWeight", "headingWeight", "contentWeight", "exactTermWeight"));
    }

    /** 从完整参数中抽取本变量组允许覆盖的字段并创建候选。 */
    private ParameterCandidate candidate(String id, RetrievalExperimentGroup group,
                                         String description, RetrievalParameters parameters,
                                         String... keys) {
        Map<String, String> full = parameters.asReportMap();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String key : keys) overrides.put(key, full.get(key));
        return new ParameterCandidate(id, parameters, new RetrievalExperimentCandidate(
                id, group, description, Map.copyOf(overrides)));
    }

    /** 判断 CJK 候选是否所有指标不退化且至少一个排名指标有清晰提升。 */
    private boolean clearlyImproves(RetrievalExperimentCandidateResult candidate,
                                    RetrievalExperimentCandidateResult baseline) {
        RetrievalEvaluationMetrics left = candidate.metrics();
        RetrievalEvaluationMetrics right = baseline.metrics();
        boolean noWorse = left.recallAt5() + EPSILON >= right.recallAt5()
                && left.mrrAt10() + EPSILON >= right.mrrAt10()
                && left.ndcgAt5() + EPSILON >= right.ndcgAt5()
                && left.noHitAccuracy() + EPSILON >= right.noHitAccuracy()
                && left.exactTermRecall() + EPSILON >= right.exactTermRecall();
        boolean clear = left.recallAt5() > right.recallAt5() + 0.005
                || left.mrrAt10() > right.mrrAt10() + 0.005
                || left.ndcgAt5() > right.ndcgAt5() + 0.005;
        return noWorse && clear;
    }

    /** 返回 nDCG、MRR、Recall、精确词和无知识的冻结质量优先排序。 */
    private Comparator<RetrievalExperimentCandidateResult> qualityComparator() {
        return Comparator.comparingDouble((RetrievalExperimentCandidateResult value) ->
                        value.metrics().ndcgAt5())
                .thenComparingDouble(value -> value.metrics().mrrAt10())
                .thenComparingDouble(value -> value.metrics().recallAt5())
                .thenComparingDouble(value -> value.metrics().exactTermRecall())
                .thenComparingDouble(value -> value.metrics().noHitAccuracy());
    }

    /** 在质量最优结果中把单次 P95 小波动视为等价，并按矩阵顺序选择更简单候选。 */
    private RetrievalExperimentCandidateResult selectBest(
            List<RetrievalExperimentCandidateResult> results) {
        RetrievalEvaluationMetrics bestMetrics = results.stream().max(qualityComparator())
                .orElseThrow().metrics();
        List<RetrievalExperimentCandidateResult> qualityTies = results.stream()
                .filter(value -> value.metrics().equals(bestMetrics)).toList();
        long fastest = qualityTies.stream().mapToLong(value -> value.retrievalLatency().p95Ms())
                .min().orElseThrow();
        double equivalentLatencyUpperBound = fastest * 1.20 + 2;
        return qualityTies.stream().filter(value -> value.retrievalLatency().p95Ms()
                <= equivalentLatencyUpperBound).findFirst().orElseThrow();
    }

    /** 判断候选是否满足全局阈值必须通过的可靠性和冻结质量门槛。 */
    private boolean passesReliabilityGates(RetrievalExperimentCandidateResult result) {
        return result.diagnostics().falseNoHitCount() == 0
                && result.diagnostics().falseGroundedCount() == 0
                && result.diagnostics().technicalFailureCount() == 0
                && result.metrics().noHitAccuracy() == 1.0
                && result.metrics().exactTermRecall() == 1.0
                && result.metrics().recallAt5() + EPSILON >= RECALL_FLOOR
                && result.metrics().mrrAt10() + EPSILON >= MRR_FLOOR
                && result.metrics().ndcgAt5() + EPSILON >= NDCG_FLOOR;
    }

    /** 断言最终锁定回归结果满足全部阶段七门禁。 */
    private void assertQualityGates(RetrievalExperimentCandidateResult result) {
        assertTrue(passesReliabilityGates(result), "最终参数未通过阶段七全部质量门禁：" + result);
    }

    /** 提取剔除耗时后的逐条结果，避免把运行调度波动误判为不可重复。 */
    private List<String> caseSemantics(RetrievalExperimentCandidateResult result) {
        return result.caseResults().stream().map(value -> value.caseId() + "|"
                + value.actualStatus() + "|" + value.rankedSourceKeys() + "|"
                + value.exactTermsSatisfied() + "|" + value.highestRerankScore() + "|"
                + value.reliableEvidenceCount() + "|" + value.decisionReason()).toList();
    }

    /** 把最终参数的两次锁定回归作为独立不可变报告保存。 */
    private void writeLockedReport(TargetRetrievalExperimentReportAdapter writer,
                                   RetrievalEvaluationDatasetSnapshot snapshot,
                                   RetrievalParameters parameters,
                                   RetrievalExperimentCandidateResult first,
                                   RetrievalExperimentCandidateResult second) {
        List<RetrievalExperimentCandidate> candidates = List.of(
                new RetrievalExperimentCandidate("locked-first",
                        RetrievalExperimentGroup.GROUNDED_THRESHOLD, "最终参数第一次锁定回归",
                        Map.of("rerankGroundedThreshold",
                                Double.toString(parameters.groundedThreshold()))),
                new RetrievalExperimentCandidate("locked-second",
                        RetrievalExperimentGroup.GROUNDED_THRESHOLD, "最终参数第二次锁定回归",
                        Map.of("rerankGroundedThreshold",
                                Double.toString(parameters.groundedThreshold()))));
        RetrievalExperimentMatrix matrix = new RetrievalExperimentMatrix("1.0",
                parameters.asReportMap(), candidates);
        Instant now = Instant.now();
        writer.write(new RetrievalExperimentReport(UUID.randomUUID(), "1.0",
                new WorkingTreeGitCommitResolver(Path.of("..")).resolve(), snapshot.version(),
                snapshot.contentSha256(), now, now, matrix,
                Map.of("embedding", "stage7-char-bigram-v1",
                        "rerank", "stage7-calibrated-char-bigram-v1"),
                        List.of(first, second)));
    }

    /** 从冻结语料创建十五个不改变正文的单分块索引文档。 */
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
            chunks.add(new IndexedKnowledgeChunk(parts[0] + ":" + sourceId + ":1:0",
                    parts[0], sourceId, 1, 0, title, title, content,
                    extractor.extract(content), "stage7-hash-" + sequence++,
                    DeterministicVector.vector(title + "\n" + content), Instant.EPOCH,
                    Instant.EPOCH, "stage7-one-chunk-v1", ExactTermExtractor.VERSION));
        }
        return List.copyOf(chunks);
    }

    /** 一个候选的参数对象及其矩阵描述。 */
    private record ParameterCandidate(String id, RetrievalParameters parameters,
                                      RetrievalExperimentCandidate matrixCandidate) { }

    /** 一个变量组执行后按矩阵顺序保存的结果。 */
    private record GroupRun(List<RetrievalExperimentCandidateResult> results) { }

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
        @Override public List<List<Double>> embedDocuments(List<String> documents,
                                                           Runnable heartbeat) {
            return documents.stream().map(DeterministicVector::vector).toList();
        }

        /** {@inheritDoc} */
        @Override public List<Double> embedQuery(String query) {
            return DeterministicVector.vector(query);
        }
    }

    /** 使用查询与候选字符向量余弦相似度执行确定性重排。 */
    private static final class DeterministicRerankModel implements RerankModelPort {
        /** {@inheritDoc} */
        @Override public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
            List<Double> queryVector = DeterministicVector.vector(query);
            return documents.stream().map(document -> new RerankScore(document.chunkId(),
                    calibrated(cosine(queryVector,
                            DeterministicVector.vector(document.content()))))).toList();
        }

        /** 把确定性余弦值单调映射到供应商 Rerank 的零到一分数尺度。 */
        private double calibrated(double cosine) {
            return Math.min(1, cosine * 4);
        }

        /** 计算两个同维归一化向量的零到一余弦值。 */
        private double cosine(List<Double> left, List<Double> right) {
            double score = 0;
            for (int index = 0; index < left.size(); index++) {
                score += left.get(index) * right.get(index);
            }
            return Math.max(0, Math.min(1, score));
        }
    }
}
