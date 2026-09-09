package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.SupportAgentApplication;
import com.lawrence.supportagent.knowledge.ExactTerm;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import com.lawrence.supportagent.retrieval.BranchStatus;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import com.lawrence.supportagent.retrieval.RetrievalResult;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 HTTP/SSE、MySQL 和 Redis 执行阶段六确定性容量基线。 */
@Testcontainers
@ActiveProfiles("dev")
@Import(StageSixBaselineIT.DeterministicAdapterConfiguration.class)
@SpringBootTest(classes = SupportAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"support-agent.dashscope.api-key=", "support-agent.async-task.enabled=false",
                "support-agent.elasticsearch.url=http://127.0.0.1:1"})
class StageSixBaselineIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent").withUsername("support_agent")
            .withPassword("test_password");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.7")).withExposedPorts(6379);

    @LocalServerPort
    private int serverPort;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MeterRegistry meters;

    /** 把容器的动态 MySQL 和 Redis 地址注入完整应用。 */
    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost()
                + ":" + REDIS.getMappedPort(6379));
    }

    /** 执行冻结的预热、单用户、并发、持续和峰值序列并写出不可变 JSON 报告。 */
    @Test
    void shouldGenerateFrozenCapacityBaseline() throws Exception {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        for (int index = 0; index < 5; index++) assertTrue(send(client).succeeded());
        List<ScenarioResult> scenarios = new ArrayList<>();
        scenarios.add(summarize("single-user", List.of(send(client))));
        for (int round = 1; round <= 3; round++) scenarios.add(concurrent(client, 5, "concurrent-5-r" + round));
        for (int round = 1; round <= 3; round++) scenarios.add(concurrent(client, 20, "concurrent-20-r" + round));
        scenarios.add(rate(client, 2, Integer.getInteger("stage6.sustained.seconds", 300), "sustained-2-rps"));
        scenarios.add(rate(client, 5, Integer.getInteger("stage6.burst.seconds", 30), "burst-5-rps"));

        assertTrue(scenarios.stream().filter(value -> value.name.startsWith("concurrent-20"))
                .allMatch(value -> value.successRate >= 0.95));
        assertTrue(scenarios.stream().allMatch(value -> value.failures.isEmpty()));
        CapacityReport report = new CapacityReport("1.0",
                new WorkingTreeGitCommitResolver(Path.of("..")).resolve(), startedAt,
                Instant.now(), elapsed(started),
                Integer.getInteger("stage6.sustained.seconds", 300),
                Integer.getInteger("stage6.burst.seconds", 30),
                Map.of("chat", "stage6-deterministic", "retrieval", "stage6-deterministic"),
                List.copyOf(scenarios), metricSnapshot(), usageSnapshot());
        writeReport(report);
    }

    /** 使用起跑闩锁同步发起一批不同会话请求。 */
    private ScenarioResult concurrent(HttpClient client, int concurrency, String name) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<RequestResult>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return send(client);
                }, executor));
            }
            start.countDown();
            return summarize(name, futures.stream().map(CompletableFuture::join).toList());
        }
    }

    /** 按固定 RPS 发起持续或峰值请求，每个虚拟线程等待自己的计划起点。 */
    private ScenarioResult rate(HttpClient client, int rps, int seconds, String name) {
        int requests = rps * seconds;
        long origin = System.nanoTime();
        long interval = TimeUnit.SECONDS.toNanos(1) / rps;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<RequestResult>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                long due = origin + index * interval;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    LockSupport.parkNanos(Math.max(0, due - System.nanoTime()));
                    return send(client);
                }, executor));
            }
            return summarize(name, futures.stream().map(CompletableFuture::join).toList());
        }
    }

    /** 发送一条使用服务端新建会话的技术支持 SSE 请求并完整消费响应。 */
    private RequestResult send(HttpClient client) {
        long started = System.nanoTime();
        try {
            String body = "{\"clientMessageId\":\"" + UUID.randomUUID()
                    + "\",\"message\":\"MySQL 连接失败怎么处理？\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + serverPort + "/api/v1/chat/stream"))
                    .header("Accept", "text/event-stream").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean succeeded = response.statusCode() == 200
                    && response.body().contains("event:answer.completed")
                    && !response.body().contains("event:error");
            return new RequestResult(succeeded, elapsed(started),
                    succeeded ? null : "HTTP_" + response.statusCode());
        } catch (Exception exception) {
            return new RequestResult(false, elapsed(started), exception.getClass().getSimpleName());
        }
    }

    /** 汇总单场景成功率、平均、P95、最大耗时和错误分类。 */
    private ScenarioResult summarize(String name, List<RequestResult> values) {
        List<Long> durations = values.stream().map(RequestResult::durationMs).sorted().toList();
        long succeeded = values.stream().filter(RequestResult::succeeded).count();
        Map<String, Long> failures = values.stream().filter(value -> !value.succeeded)
                .collect(java.util.stream.Collectors.groupingBy(RequestResult::failure,
                        LinkedHashMap::new, java.util.stream.Collectors.counting()));
        int p95Index = Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1);
        return new ScenarioResult(name, values.size(), succeeded,
                succeeded / (double) values.size(),
                durations.stream().mapToLong(Long::longValue).average().orElse(0),
                durations.get(p95Index), durations.getLast(), Map.copyOf(failures));
    }

    /** 读取关键 Micrometer 计时器的样本数和总耗时，不导出任何业务正文。 */
    private Map<String, MetricValue> metricSnapshot() {
        Map<String, MetricValue> values = new LinkedHashMap<>();
        for (Operation operation : Operation.values()) {
            var success = meters.find("support.agent.optimization.duration")
                    .tag("operation", operation.name()).tag("outcome", "success").timer();
            var failure = meters.find("support.agent.optimization.duration")
                    .tag("operation", operation.name()).tag("outcome", "failure").timer();
            long successCount = success == null ? 0 : success.count();
            long failureCount = failure == null ? 0 : failure.count();
            double total = success == null ? 0 : success.totalTime(TimeUnit.MILLISECONDS);
            double mean = success == null ? 0 : success.mean(TimeUnit.MILLISECONDS);
            double maximum = success == null ? 0 : success.max(TimeUnit.MILLISECONDS);
            double p95 = percentile(success, 0.95);
            values.put(operation.name(), new MetricValue(successCount, failureCount,
                    total, mean, p95, maximum));
        }
        return Map.copyOf(values);
    }

    /** 从成功计时器快照读取指定分位值，未产生样本时返回零。 */
    private double percentile(io.micrometer.core.instrument.Timer timer, double percentile) {
        if (timer == null) return 0;
        return java.util.Arrays.stream(timer.takeSnapshot().percentileValues())
                .filter(value -> Double.compare(value.percentile(), percentile) == 0)
                .mapToDouble(value -> value.value(TimeUnit.MILLISECONDS)).findFirst().orElse(0);
    }

    /** 读取模型用量汇总，只保留调用次数和数值合计。 */
    private Map<String, UsageValue> usageSnapshot() {
        Map<String, UsageValue> values = new LinkedHashMap<>();
        for (ModelOperation operation : ModelOperation.values()) {
            values.put(operation.name(), new UsageValue(
                    summary("support.agent.optimization.model.input.tokens", operation),
                    summary("support.agent.optimization.model.output.tokens", operation),
                    summary("support.agent.optimization.model.items", operation),
                    summary("support.agent.optimization.model.characters", operation)));
        }
        return Map.copyOf(values);
    }

    /** 汇总指定模型操作的分布指标样本数与总量。 */
    private SummaryValue summary(String name, ModelOperation operation) {
        var summaries = meters.find(name).tag("operation", operation.name()).summaries();
        long count = summaries.stream().mapToLong(io.micrometer.core.instrument.DistributionSummary::count).sum();
        double total = summaries.stream()
                .mapToDouble(io.micrometer.core.instrument.DistributionSummary::totalAmount).sum();
        double average = count == 0 ? 0 : total / count;
        double maximum = summaries.stream()
                .mapToDouble(io.micrometer.core.instrument.DistributionSummary::max).max().orElse(0);
        double p95 = summaries.stream().findFirst().map(value -> java.util.Arrays.stream(
                        value.takeSnapshot().percentileValues())
                .filter(point -> Double.compare(point.percentile(), 0.95) == 0)
                .mapToDouble(io.micrometer.core.instrument.distribution.ValueAtPercentile::value)
                .findFirst().orElse(0.0)).orElse(0.0);
        return new SummaryValue(count, total, average, p95, maximum);
    }

    /** 把容量结果写入带 UUID 的 target 路径并禁止覆盖既有报告。 */
    private void writeReport(CapacityReport report) throws Exception {
        Path directory = Path.of("target", "stage-6-baseline");
        Files.createDirectories(directory);
        Path output = directory.resolve("capacity-" + UUID.randomUUID() + ".json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertTrue(Files.size(output) > 0);
    }

    /** 等待起跑闩锁并把中断恢复为明确失败。 */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("容量测试起跑等待被中断", exception);
        }
    }

    /** 将单调时钟纳秒差转换为非负毫秒。 */
    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    /** 为容量测试注入确定性检索和 Chat 适配器，避免调用真实 DashScope。 */
    @TestConfiguration
    static class DeterministicAdapterConfiguration {
        /** 创建会回调首 Token 并记录固定聚合用量的确定性 Chat 端口。 */
        @Bean @Primary
        ChatModelPort deterministicChatModel(OptimizationTelemetryPort telemetry) {
            return new DeterministicChatModel(telemetry);
        }

        /** 创建返回单一稳定证据的确定性检索服务。 */
        @Bean @Primary
        RetrievalService deterministicRetrievalService(OptimizationTelemetryPort telemetry) {
            RetrievalService service = mock(RetrievalService.class);
            when(service.retrieve(anyString())).thenAnswer(invocation -> {
                telemetry.recordDuration(Operation.RETRIEVAL, 1, true);
                RetrievalEvidence evidence = new RetrievalEvidence("capacity-chunk", "MANAGED_DOCUMENT",
                        1, 1, "MySQL 连接故障标准", "连接排查", "请检查 MySQL 连接配置。",
                        List.<ExactTerm>of(), Set.of("content"), 1, 1, 0.03, 0.99);
                return new RetrievalResult(RetrievalStatus.GROUNDED, BranchStatus.SUCCEEDED,
                        BranchStatus.SUCCEEDED, BranchStatus.SUCCEEDED,
                        List.of(evidence), List.of(evidence), 1);
            });
            return service;
        }
    }

    /** 只返回可通过引用校验的固定答案，并模拟可观测的首 Token 与 Token 用量。 */
    private static final class DeterministicChatModel implements ChatModelPort {
        private final OptimizationTelemetryPort telemetry;

        /** 注入测试运行使用的低基数遥测端口。 */
        private DeterministicChatModel(OptimizationTelemetryPort telemetry) { this.telemetry = telemetry; }

        /** {@inheritDoc} */
        @Override public ModelAnswer greeting(String message, List<String> recentTurns, Runnable firstToken) {
            return answer(firstToken);
        }
        /** {@inheritDoc} */
        @Override public ModelAnswer groundedAnswer(String message, List<String> recentTurns,
                List<RetrievalEvidence> evidence, String feedback, Runnable firstToken) {
            return answer(firstToken);
        }
        /** {@inheritDoc} */
        @Override public ModelAnswer ticketAnswer(String message, String ticketNo,
                com.lawrence.supportagent.ticket.TicketDetails ticket, List<String> recentTurns,
                Runnable firstToken) { return answer(firstToken); }
        /** {@inheritDoc} */
        @Override public TicketDraft generateTicketDraft(String frozenContext) {
            return new TicketDraft("固定标题", "固定问题", "固定操作");
        }
        /** {@inheritDoc} */
        @Override public ResolvedCaseDraft generateResolvedCaseDraft(String ticketFacts) {
            return new ResolvedCaseDraft("固定案例", "固定问题");
        }
        /** 产生固定首 Token 回调、用量和带引用答案。 */
        private ModelAnswer answer(Runnable firstToken) {
            firstToken.run();
            telemetry.recordUsage(ModelOperation.CHAT, 12, 10, 1, 42);
            return new ModelAnswer("请检查 MySQL 连接配置。[S1]", "stage6-deterministic-v1");
        }
    }

    /** @param succeeded 是否完整收到完成事件 @param durationMs 完整响应耗时 @param failure 错误分类 */
    private record RequestResult(boolean succeeded, long durationMs, String failure) { }

    /** 场景级容量统计。 */
    private record ScenarioResult(String name, int requests, long successes, double successRate,
                                  double averageMs, long p95Ms, long maximumMs,
                                  Map<String, Long> failures) { }

    /** 关键 Micrometer 指标快照。 */
    private record MetricValue(long successCount, long failureCount, double totalDurationMs,
                               double averageMs, double p95Ms, double maximumMs) { }

    /** 单个模型用量指标的样本数和合计值。 */
    private record SummaryValue(long sampleCount, double totalAmount, double average,
                                double p95, double maximum) { }

    /** 单类模型调用的 Token、条目和字符汇总。 */
    private record UsageValue(SummaryValue inputTokens, SummaryValue outputTokens,
                              SummaryValue items, SummaryValue characters) { }

    /** 阶段六不可变容量报告。 */
    private record CapacityReport(String schemaVersion, String gitCommit, Instant startedAt,
                                  Instant finishedAt, long durationMs,
                                  int sustainedSeconds, int burstSeconds,
                                  Map<String, String> modelNames,
                                  List<ScenarioResult> scenarios,
                                  Map<String, MetricValue> telemetry,
                                  Map<String, UsageValue> modelUsage) { }
}
