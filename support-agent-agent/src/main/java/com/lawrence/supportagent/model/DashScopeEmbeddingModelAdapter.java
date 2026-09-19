package com.lawrence.supportagent.model;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** 使用 DashScope 官方 Java SDK 批量生成 1024 维文本向量。 */
public class DashScopeEmbeddingModelAdapter implements EmbeddingModelPort, AutoCloseable {
    private static final int DIMENSIONS = 1024;
    private static final int MAX_BATCH_SIZE = 10;
    private static final int DEFAULT_MAX_CALL_ATTEMPTS = 3;
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RETRY_INITIAL_DELAY = Duration.ofMillis(100);
    private final String apiKey;
    private final String modelName;
    private final SdkCaller sdkCaller;
    private final OptimizationTelemetryPort telemetry;
    private final Duration callTimeout;
    private final int maxCallAttempts;
    private final Duration retryInitialDelay;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    /** 保存不会写入日志的 API 密钥和冻结模型名称。 */
    public DashScopeEmbeddingModelAdapter(String apiKey, String modelName) {
        this(apiKey, modelName, param -> new TextEmbedding().call(param),
                OptimizationTelemetryPort.noOp(), DEFAULT_CALL_TIMEOUT,
                DEFAULT_MAX_CALL_ATTEMPTS, DEFAULT_RETRY_INITIAL_DELAY);
    }

    /** 使用显式 Base URL 构造 Embedding 客户端。 */
    public DashScopeEmbeddingModelAdapter(String apiKey, String modelName, String baseUrl) {
        this(apiKey, modelName, baseUrl, OptimizationTelemetryPort.noOp());
    }

    /** 使用显式 Base URL 和低基数遥测端口构造 Embedding 客户端。 */
    public DashScopeEmbeddingModelAdapter(String apiKey, String modelName, String baseUrl,
                                           OptimizationTelemetryPort telemetry) {
        this(apiKey, modelName, baseUrl, telemetry, DEFAULT_CALL_TIMEOUT,
                DEFAULT_MAX_CALL_ATTEMPTS, DEFAULT_RETRY_INITIAL_DELAY);
    }

    /** 使用显式 Base URL、遥测端口、总超时和有限重试策略构造适配器。 */
    public DashScopeEmbeddingModelAdapter(String apiKey, String modelName, String baseUrl,
                                           OptimizationTelemetryPort telemetry,
                                           Duration callTimeout, int maxCallAttempts,
                                           Duration retryInitialDelay) {
        this(apiKey, modelName, param -> new TextEmbedding(baseUrl).call(param), telemetry,
                callTimeout, maxCallAttempts, retryInitialDelay);
    }

    /** 注入测试可替换的官方 SDK 调用边界。 */
    DashScopeEmbeddingModelAdapter(String apiKey, String modelName, SdkCaller sdkCaller) {
        this(apiKey, modelName, sdkCaller, OptimizationTelemetryPort.noOp(),
                DEFAULT_CALL_TIMEOUT, DEFAULT_MAX_CALL_ATTEMPTS,
                DEFAULT_RETRY_INITIAL_DELAY);
    }

    /** 注入测试可替换的 SDK 调用边界和遥测端口。 */
    DashScopeEmbeddingModelAdapter(String apiKey, String modelName, SdkCaller sdkCaller,
                                   OptimizationTelemetryPort telemetry) {
        this(apiKey, modelName, sdkCaller, telemetry, DEFAULT_CALL_TIMEOUT,
                DEFAULT_MAX_CALL_ATTEMPTS, DEFAULT_RETRY_INITIAL_DELAY);
    }

    /** 注入可替换调用边界以及显式超时和重试参数。 */
    DashScopeEmbeddingModelAdapter(String apiKey, String modelName, SdkCaller sdkCaller,
                                   OptimizationTelemetryPort telemetry, Duration callTimeout,
                                   int maxCallAttempts, Duration retryInitialDelay) {
        validateOperationalSettings(callTimeout, maxCallAttempts, retryInitialDelay);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.sdkCaller = sdkCaller;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
        this.callTimeout = callTimeout;
        this.maxCallAttempts = maxCallAttempts;
        this.retryInitialDelay = retryInitialDelay;
    }

    /** {@inheritDoc} */
    @Override
    public List<List<Double>> embedDocuments(List<String> documents, Runnable batchHeartbeat) {
        if (documents == null || documents.isEmpty()
                || documents.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Embedding 文档列表不能为空且不能包含空正文");
        }
        Runnable heartbeat = batchHeartbeat == null ? () -> { } : batchHeartbeat;
        List<List<Double>> result = new ArrayList<>(documents.size());
        for (int start = 0; start < documents.size(); start += MAX_BATCH_SIZE) {
            heartbeat.run();
            int end = Math.min(start + MAX_BATCH_SIZE, documents.size());
            result.addAll(callBatch(documents.subList(start, end),
                    TextEmbeddingParam.TextType.DOCUMENT));
        }
        return List.copyOf(result);
    }

    /** {@inheritDoc} */
    @Override
    public List<Double> embedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Embedding 查询不能为空");
        }
        return callBatch(List.of(query), TextEmbeddingParam.TextType.QUERY).getFirst();
    }

    /** 关闭用于落实单次调用超时的虚拟线程执行器。 */
    @Override
    public void close() {
        calls.close();
    }

    /** 按配置的总超时和有限抖动重试调用一个不超过十条的批次。 */
    private List<List<Double>> callBatch(List<String> inputs, TextEmbeddingParam.TextType textType) {
        requireConfigured();
        ModelInvocationException lastFailure = null;
        for (int attempt = 1; attempt <= maxCallAttempts; attempt++) {
            try {
                return timedCall(inputs, textType);
            } catch (ModelInvocationException exception) {
                lastFailure = exception;
                if (!exception.retryable() || attempt == maxCallAttempts) {
                    throw exception;
                }
                awaitRetry(attempt);
            }
        }
        throw lastFailure;
    }

    /** 在独立虚拟线程中执行 SDK 同步调用并强制总超时。 */
    private List<List<Double>> timedCall(List<String> inputs,
                                         TextEmbeddingParam.TextType textType) {
        long started = System.nanoTime();
        Future<List<List<Double>>> future = calls.submit(() -> invokeSdk(inputs, textType));
        try {
            List<List<Double>> result = future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            telemetry.recordDuration(Operation.EMBEDDING, elapsed(started), true);
            return result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            telemetry.recordDuration(Operation.EMBEDDING, elapsed(started), false);
            throw new ModelInvocationException("EMBEDDING_TIMEOUT",
                    "Embedding 调用超时", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            telemetry.recordDuration(Operation.EMBEDDING, elapsed(started), false);
            throw new ModelInvocationException("EMBEDDING_INTERRUPTED",
                    "Embedding 调用被中断", false, exception);
        } catch (ExecutionException exception) {
            telemetry.recordDuration(Operation.EMBEDDING, elapsed(started), false);
            throw classify(exception.getCause());
        }
    }

    /** 构造官方 SDK 请求并按响应 textIndex 恢复原输入顺序。 */
    private List<List<Double>> invokeSdk(List<String> inputs,
                                         TextEmbeddingParam.TextType textType)
            throws NoApiKeyException {
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .texts(inputs)
                .textType(textType)
                .dimension(DIMENSIONS)
                .outputType(TextEmbeddingParam.OutputType.DENSE)
                .build();
        TextEmbeddingResult result = sdkCaller.call(param);
        if (result == null || result.getStatusCode() == null
                || result.getStatusCode() < 200 || result.getStatusCode() >= 300) {
            int status = result == null || result.getStatusCode() == null
                    ? 0 : result.getStatusCode();
            throw new ModelInvocationException("EMBEDDING_PROVIDER_FAILURE",
                    "Embedding 服务暂时不可用", status == 0 || status == 429 || status >= 500, null);
        }
        List<List<Double>> vectors = orderedEmbeddings(inputs.size(), result.getOutput());
        long tokens = result.getUsage() == null || result.getUsage().getTotalTokens() == null
                ? 0 : result.getUsage().getTotalTokens();
        long characters = inputs.stream().mapToLong(String::length).sum();
        telemetry.recordUsage(ModelOperation.EMBEDDING, tokens, 0, inputs.size(), characters);
        return vectors;
    }

    /** 校验响应索引唯一完整后返回不可变的有序向量列表。 */
    private List<List<Double>> orderedEmbeddings(int expectedCount, TextEmbeddingOutput output) {
        List<TextEmbeddingResultItem> items = output == null ? null : output.getEmbeddings();
        if (items == null || items.size() != expectedCount) {
            throw invalidResponse("Embedding 返回数量与输入不一致");
        }
        if (items.stream().anyMatch(item -> item == null || item.getTextIndex() == null)) {
            throw invalidResponse("Embedding 返回顺序索引不合法");
        }
        List<TextEmbeddingResultItem> ordered = items.stream()
                .sorted(Comparator.comparingInt(TextEmbeddingResultItem::getTextIndex)).toList();
        Set<Integer> indexes = new HashSet<>();
        List<List<Double>> vectors = new ArrayList<>(expectedCount);
        for (int expected = 0; expected < ordered.size(); expected++) {
            TextEmbeddingResultItem item = ordered.get(expected);
            if (item.getTextIndex() == null || item.getTextIndex() != expected
                    || !indexes.add(item.getTextIndex()) || item.getEmbedding() == null
                    || item.getEmbedding().size() != DIMENSIONS) {
                throw invalidResponse("Embedding 返回顺序或维度不合法");
            }
            vectors.add(List.copyOf(item.getEmbedding()));
        }
        return List.copyOf(vectors);
    }

    /** 将 SDK 或网络异常转换为不泄露原始响应的稳定错误。 */
    private ModelInvocationException classify(Throwable failure) {
        if (failure instanceof ModelInvocationException exception) {
            return exception;
        }
        if (failure instanceof NoApiKeyException) {
            return new ModelInvocationException("DASHSCOPE_NOT_CONFIGURED",
                    "当前环境未配置 DashScope 密钥", false, failure);
        }
        if (failure instanceof ApiException exception) {
            int status = exception.getStatus() == null ? 0 : exception.getStatus().getStatusCode();
            return new ModelInvocationException("EMBEDDING_PROVIDER_FAILURE",
                    "Embedding 服务暂时不可用", status == 0 || status == 429 || status >= 500, failure);
        }
        return new ModelInvocationException("EMBEDDING_PROVIDER_FAILURE",
                "Embedding 服务暂时不可用", true, failure);
    }

    /** 在发起付费模型调用前验证密钥配置存在。 */
    private void requireConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelInvocationException("DASHSCOPE_NOT_CONFIGURED",
                    "当前环境未配置 DashScope 密钥", false, null);
        }
    }

    /** 在指数退避基础上增加零到基础延迟的随机抖动，避免并发请求同步重试。 */
    private void awaitRetry(int failedAttempt) {
        long multiplier = 1L << Math.min(failedAttempt - 1, 10);
        long baseMillis = Math.multiplyExact(retryInitialDelay.toMillis(), multiplier);
        long jitterMillis = ThreadLocalRandom.current().nextLong(baseMillis + 1);
        try {
            Thread.sleep(baseMillis + jitterMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelInvocationException("EMBEDDING_INTERRUPTED",
                    "Embedding 重试等待被中断", false, exception);
        }
    }

    /** 校验适配器运行参数，测试直连构造也不得绕过启动配置约束。 */
    private void validateOperationalSettings(Duration timeout, int attempts, Duration delay) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || attempts < 1 || attempts > 3 || delay == null
                || delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("Embedding 超时或重试配置不合法");
        }
    }

    /** 创建不可重试的响应结构错误。 */
    private ModelInvocationException invalidResponse(String message) {
        return new ModelInvocationException("EMBEDDING_RESULT_INVALID", message, false, null);
    }

    /** 将单调时钟纳秒差转换为非负毫秒。 */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 隔离官方 SDK 同步调用，供离线协议测试替换网络。 */
    @FunctionalInterface
    interface SdkCaller {
        /** 使用已构造参数调用官方 SDK。 */
        TextEmbeddingResult call(TextEmbeddingParam param) throws NoApiKeyException;
    }
}
