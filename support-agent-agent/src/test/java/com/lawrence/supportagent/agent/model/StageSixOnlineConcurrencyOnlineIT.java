package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** 在显式提供真实密钥时抽样验证一、五、二十路 DashScope 并发。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class StageSixOnlineConcurrencyOnlineIT {
    /** 执行三个同步起跑批次并限制报告只包含聚合耗时与 Token。 */
    @Test
    void shouldSampleRealDashScopeConcurrencyWithinSmallBudget() throws Exception {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        UsageTelemetry telemetry = new UsageTelemetry();
        DashScopeChatModelAdapter adapter = new DashScopeChatModelAdapter(
                System.getenv("DASHSCOPE_API_KEY"), modelName(), baseUrl(),
                new ObjectMapper(), telemetry);
        List<BatchResult> batches = List.of(batch(adapter, 1), batch(adapter, 5), batch(adapter, 20));
        assertThat(batches.get(0).successRate).isEqualTo(1.0);
        assertThat(batches.get(1).successRate).isEqualTo(1.0);
        assertThat(batches.get(2).successRate).isGreaterThanOrEqualTo(0.95);
        OnlineReport report = new OnlineReport("1.0", modelName(), startedAt, Instant.now(),
                elapsed(started), batches,
                telemetry.inputTokens.sum(), telemetry.outputTokens.sum());
        Path directory = Path.of("target", "stage-6-online-baseline");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("online-" + UUID.randomUUID() + ".json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** 使用起跑闩锁并发发送独立、无历史上下文的问候请求。 */
    private BatchResult batch(DashScopeChatModelAdapter adapter, int concurrency) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<CallResult>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                int requestNumber = index + 1;
                futures.add(CompletableFuture.supplyAsync(() -> call(adapter, start, requestNumber), executor));
            }
            start.countDown();
            List<CallResult> calls = futures.stream().map(CompletableFuture::join).toList();
            long successes = calls.stream().filter(CallResult::succeeded).count();
            List<Long> latency = calls.stream().map(CallResult::durationMs).sorted().toList();
            Map<String, Long> failures = calls.stream().filter(value -> !value.succeeded)
                    .collect(java.util.stream.Collectors.groupingBy(CallResult::failure,
                            LinkedHashMap::new, java.util.stream.Collectors.counting()));
            int p95 = Math.max(0, (int) Math.ceil(latency.size() * 0.95) - 1);
            return new BatchResult(concurrency, successes, successes / (double) concurrency,
                    latency.get(p95), latency.getLast(), Map.copyOf(failures));
        }
    }

    /** 等待同步起点并执行一次真实模型调用。 */
    private CallResult call(DashScopeChatModelAdapter adapter, CountDownLatch start, int number) {
        long started = System.nanoTime();
        try {
            start.await();
            String answer = adapter.greeting("你好，请用一句话说明你能提供技术支持。请求编号" + number,
                    List.of(), () -> { }).text();
            return new CallResult(answer != null && !answer.isBlank(), elapsed(started), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CallResult(false, elapsed(started), "InterruptedException");
        } catch (RuntimeException exception) {
            return new CallResult(false, elapsed(started), exception.getClass().getSimpleName());
        }
    }

    /** 返回在线测试显式模型，默认保持当前一期基线。 */
    private String modelName() {
        return System.getenv().getOrDefault("SUPPORT_AGENT_CHAT_MODEL", "qwen3.7-flash-2026-07-15");
    }

    /** 返回在线测试显式原生 DashScope 地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }

    /** 将单调时钟纳秒差转换为非负毫秒。 */
    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    /** 只累加 Chat Token，不存储任何请求或响应正文。 */
    private static final class UsageTelemetry implements OptimizationTelemetryPort {
        private final LongAdder inputTokens = new LongAdder();
        private final LongAdder outputTokens = new LongAdder();

        /** {@inheritDoc} */
        @Override public void recordDuration(Operation operation, long durationMs, boolean succeeded) { }
        /** {@inheritDoc} */
        @Override public void recordUsage(ModelOperation operation, long input, long output,
                                          long items, long characters) {
            if (operation == ModelOperation.CHAT) {
                inputTokens.add(input);
                outputTokens.add(output);
            }
        }
    }

    /** 单次在线调用结果。 */
    private record CallResult(boolean succeeded, long durationMs, String failure) { }

    /** 单个并发批次结果。 */
    private record BatchResult(int concurrency, long successes, double successRate,
                               long p95Ms, long maximumMs, Map<String, Long> failures) { }

    /** 不含正文的真实在线并发报告。 */
    private record OnlineReport(String schemaVersion, String modelName, Instant startedAt,
                                Instant finishedAt, long durationMs,
                                List<BatchResult> batches, long inputTokens, long outputTokens) { }
}
