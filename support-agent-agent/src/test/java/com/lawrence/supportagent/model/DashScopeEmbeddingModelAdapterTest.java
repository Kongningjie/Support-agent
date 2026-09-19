package com.lawrence.supportagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 离线验证 DashScope Embedding 批量上限、顺序和响应结构门禁。 */
class DashScopeEmbeddingModelAdapterTest {
    /** 验证十一条文档拆成 10+1 两批并按 textIndex 恢复输入顺序。 */
    @Test
    void shouldBatchAtTenAndRestoreProviderOrder() {
        List<Integer> batchSizes = new ArrayList<>();
        AtomicInteger heartbeats = new AtomicInteger();
        try (DashScopeEmbeddingModelAdapter adapter = new DashScopeEmbeddingModelAdapter(
                "test-key", "text-embedding-v4", param -> {
                    int size = param.getInput().getAsJsonArray("texts").size();
                    batchSizes.add(size);
                    return successfulResult(size, true);
                })) {
            List<List<Double>> result = adapter.embedDocuments(
                    java.util.stream.IntStream.range(0, 11).mapToObj(i -> "文档" + i).toList(),
                    heartbeats::incrementAndGet);

            assertEquals(List.of(10, 1), batchSizes);
            assertEquals(2, heartbeats.get());
            assertEquals(11, result.size());
        }
    }

    /** 验证缺失或重复响应位置会被判为不可重试结构错误。 */
    @Test
    void shouldRejectInvalidProviderIndexes() {
        try (DashScopeEmbeddingModelAdapter adapter = new DashScopeEmbeddingModelAdapter(
                "test-key", "text-embedding-v4", param -> successfulResult(2, false))) {
            ModelInvocationException exception = assertThrows(ModelInvocationException.class,
                    () -> adapter.embedDocuments(List.of("一", "二"), null));

            assertEquals("EMBEDDING_RESULT_INVALID", exception.errorCode());
            assertEquals(false, exception.retryable());
        }
    }

    /** 验证临时 SDK 故障最多重试两次后可返回成功结果。 */
    @Test
    void shouldRetryTemporaryFailureTwice() {
        AtomicInteger calls = new AtomicInteger();
        try (DashScopeEmbeddingModelAdapter adapter = new DashScopeEmbeddingModelAdapter(
                "test-key", "text-embedding-v4", param -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new IllegalStateException("temporary");
                    }
                    return successfulResult(1, true);
                })) {
            List<Double> result = adapter.embedQuery("查询");

            assertEquals(3, calls.get());
            assertEquals(1024, result.size());
        }
    }

    /** 验证配置的单次超时会取消等待并返回稳定可重试错误。 */
    @Test
    void shouldEnforceConfiguredCallTimeout() {
        try (DashScopeEmbeddingModelAdapter adapter = new DashScopeEmbeddingModelAdapter(
                "test-key", "text-embedding-v4", param -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return successfulResult(1, true);
                }, null, Duration.ofMillis(20), 1, Duration.ofMillis(1))) {
            ModelInvocationException exception = assertThrows(ModelInvocationException.class,
                    () -> adapter.embedQuery("查询"));

            assertEquals("EMBEDDING_TIMEOUT", exception.errorCode());
            assertEquals(true, exception.retryable());
        }
    }

    /** 创建向量满足 1024 维且可选择倒序或重复索引的 SDK 响应。 */
    private TextEmbeddingResult successfulResult(int size, boolean validIndexes) {
        List<TextEmbeddingResultItem> items = new ArrayList<>();
        for (int index = size - 1; index >= 0; index--) {
            TextEmbeddingResultItem item = new TextEmbeddingResultItem();
            item.setTextIndex(validIndexes ? index : 0);
            item.setEmbedding(Collections.nCopies(1024, (double) index));
            items.add(item);
        }
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(items);
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getStatusCode()).thenReturn(200);
        when(result.getOutput()).thenReturn(output);
        return result;
    }
}
