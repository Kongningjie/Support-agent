package com.lawrence.supportagent.model;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 使用 DashScope 官方 SDK 实现候选分块重排。 */
public class DashScopeRerankModelAdapter implements RerankModelPort {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_RETRY_INITIAL_DELAY = Duration.ofMillis(100);
    private final String apiKey;
    private final String modelName;
    private final SdkCaller sdkCaller;
    private final OptimizationTelemetryPort telemetry;
    private final int maxAttempts;
    private final Duration retryInitialDelay;

    /** 使用显式 Base URL 构造 SDK 客户端，避免环境地址未传入适配器。 */
    public DashScopeRerankModelAdapter(String apiKey, String modelName, String baseUrl) {
        this(apiKey, modelName, baseUrl, OptimizationTelemetryPort.noOp());
    }

    /** 使用显式 Base URL 和低基数遥测端口构造 Rerank 客户端。 */
    public DashScopeRerankModelAdapter(String apiKey, String modelName, String baseUrl,
                                       OptimizationTelemetryPort telemetry) {
        this(apiKey, modelName, baseUrl, telemetry, DEFAULT_TIMEOUT,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_INITIAL_DELAY);
    }

    /** 使用显式网络超时和有限抖动重试策略构造 Rerank 客户端。 */
    public DashScopeRerankModelAdapter(String apiKey, String modelName, String baseUrl,
                                       OptimizationTelemetryPort telemetry, Duration timeout,
                                       int maxAttempts, Duration retryInitialDelay) {
        this(apiKey, modelName, createCaller(baseUrl, timeout), telemetry,
                maxAttempts, retryInitialDelay);
    }

    /** 注入可替换 SDK 调用边界以及显式重试参数。 */
    DashScopeRerankModelAdapter(String apiKey, String modelName, SdkCaller sdkCaller,
                                OptimizationTelemetryPort telemetry, int maxAttempts,
                                Duration retryInitialDelay) {
        validateOperationalSettings(maxAttempts, retryInitialDelay);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.sdkCaller = sdkCaller;
        this.telemetry = telemetry == null ? OptimizationTelemetryPort.noOp() : telemetry;
        this.maxAttempts = maxAttempts;
        this.retryInitialDelay = retryInitialDelay;
    }

    /** {@inheritDoc} */
    @Override
    public List<RerankScore> rerank(String query, List<RerankDocument> documents) {
        if (apiKey == null || apiKey.isBlank()) throw new ModelInvocationException(
                "DASHSCOPE_NOT_CONFIGURED", "当前环境未配置 DashScope 密钥", false, null);
        long started = System.nanoTime();
        try {
            List<RerankScore> scores = callWithRetry(query, documents);
            telemetry.recordDuration(Operation.RERANK, elapsed(started), true);
            return scores;
        } catch (ModelInvocationException exception) {
            telemetry.recordDuration(Operation.RERANK, elapsed(started), false);
            throw exception;
        } catch (Exception exception) {
            telemetry.recordDuration(Operation.RERANK, elapsed(started), false);
            throw new ModelInvocationException("RERANK_PROVIDER_FAILURE", "Rerank 服务暂时不可用", true, exception);
        }
    }

    /** 对 Rerank 幂等只读请求执行有限重试，不重试结构错误和鉴权错误。 */
    private List<RerankScore> callWithRetry(String query, List<RerankDocument> documents) {
        ModelInvocationException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invoke(query, documents);
            } catch (ModelInvocationException exception) {
                lastFailure = exception;
                if (!exception.retryable() || attempt == maxAttempts) {
                    throw exception;
                }
                awaitRetry(attempt);
            }
        }
        throw lastFailure;
    }

    /** 构造一次请求、校验供应商响应并转换为稳定的分块评分。 */
    private List<RerankScore> invoke(String query, List<RerankDocument> documents) {
        TextReRankParam parameter = TextReRankParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .query(query)
                .documents(documents.stream().map(RerankDocument::content).toList())
                .topN(documents.size())
                .returnDocuments(false)
                .build();
        TextReRankResult result;
        try {
            result = sdkCaller.call(parameter);
        } catch (Exception exception) {
            throw classify(exception);
        }
        int status = result == null || result.getStatusCode() == null ? 0 : result.getStatusCode();
        if (status < 200 || status >= 300 || result.getOutput() == null
                || result.getOutput().getResults() == null) {
            throw new ModelInvocationException("RERANK_PROVIDER_FAILURE",
                    "Rerank 服务暂时不可用", status == 0 || status == 429 || status >= 500, null);
        }
        List<RerankScore> scores = new ArrayList<>();
        for (TextReRankOutput.Result item : result.getOutput().getResults()) {
            if (item.getIndex() == null || item.getIndex() < 0 || item.getIndex() >= documents.size()
                    || item.getRelevanceScore() == null) {
                throw new ModelInvocationException("RERANK_RESULT_INVALID",
                        "Rerank 返回结构无效", false, null);
            }
            scores.add(new RerankScore(documents.get(item.getIndex()).chunkId(), item.getRelevanceScore()));
        }
        long tokens = result.getUsage() == null || result.getUsage().getTotalTokens() == null
                ? 0 : result.getUsage().getTotalTokens();
        long characters = query.length() + documents.stream()
                .mapToLong(value -> value.content().length()).sum();
        telemetry.recordUsage(ModelOperation.RERANK, tokens, 0, documents.size(), characters);
        return List.copyOf(scores);
    }

    /** 将 SDK 异常转换为不泄露供应商响应的稳定错误分类。 */
    private ModelInvocationException classify(Exception failure) {
        if (failure instanceof NoApiKeyException) {
            return new ModelInvocationException("DASHSCOPE_NOT_CONFIGURED",
                    "当前环境未配置 DashScope 密钥", false, failure);
        }
        if (failure instanceof ApiException exception) {
            int status = exception.getStatus() == null ? 0 : exception.getStatus().getStatusCode();
            return new ModelInvocationException("RERANK_PROVIDER_FAILURE",
                    "Rerank 服务暂时不可用", status == 0 || status == 429 || status >= 500, failure);
        }
        return new ModelInvocationException("RERANK_PROVIDER_FAILURE",
                "Rerank 服务暂时不可用", true, failure);
    }

    /** 在指数退避基础上增加随机抖动，避免并发请求同步重试。 */
    private void awaitRetry(int failedAttempt) {
        long multiplier = 1L << Math.min(failedAttempt - 1, 10);
        long baseMillis = Math.multiplyExact(retryInitialDelay.toMillis(), multiplier);
        long jitterMillis = ThreadLocalRandom.current().nextLong(baseMillis + 1);
        try {
            Thread.sleep(baseMillis + jitterMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelInvocationException("RERANK_INTERRUPTED",
                    "Rerank 重试等待被中断", false, exception);
        }
    }

    /** 创建带显式连接、写入和读取超时的官方 SDK 调用边界。 */
    private static SdkCaller createCaller(String baseUrl, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Rerank 超时必须大于 0");
        }
        ConnectionOptions options = ConnectionOptions.builder()
                .connectTimeout(timeout)
                .writeTimeout(timeout)
                .readTimeout(timeout)
                .build();
        TextReRank client = new TextReRank("http", baseUrl, options);
        return client::call;
    }

    /** 校验有限重试参数不允许形成无限或高放大调用。 */
    private void validateOperationalSettings(int attempts, Duration delay) {
        if (attempts < 1 || attempts > 3 || delay == null
                || delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("Rerank 重试配置不合法");
        }
    }

    /** 将单调时钟纳秒差转换为非负毫秒。 */
    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 隔离官方 SDK 同步调用，供超时和重试测试替换网络。 */
    @FunctionalInterface
    interface SdkCaller {
        /** 使用已构造参数调用官方 SDK。 */
        TextReRankResult call(TextReRankParam parameter) throws Exception;
    }
}
