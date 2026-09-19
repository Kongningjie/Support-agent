package com.lawrence.supportagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 离线验证 Rerank 有限重试和响应结构门禁。 */
class DashScopeRerankModelAdapterTest {
    /** 验证临时网络故障可有限重试后成功，且返回分块关联不变。 */
    @Test
    void shouldRetryTemporaryFailure() {
        AtomicInteger calls = new AtomicInteger();
        DashScopeRerankModelAdapter adapter = new DashScopeRerankModelAdapter(
                "test-key", "qwen3-rerank", parameter -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                    return successfulResult();
                }, null, 2, Duration.ofMillis(1));

        List<RerankModelPort.RerankScore> scores = adapter.rerank("查询",
                List.of(new RerankModelPort.RerankDocument("chunk-1", "正文")));

        assertEquals(2, calls.get());
        assertEquals("chunk-1", scores.getFirst().chunkId());
    }

    /** 验证供应商结构错误不会重试，避免放大确定性失败。 */
    @Test
    void shouldNotRetryInvalidResult() {
        AtomicInteger calls = new AtomicInteger();
        DashScopeRerankModelAdapter adapter = new DashScopeRerankModelAdapter(
                "test-key", "qwen3-rerank", parameter -> {
                    calls.incrementAndGet();
                    TextReRankResult result = successfulResult();
                    result.getOutput().getResults().getFirst().setIndex(3);
                    return result;
                }, null, 3, Duration.ofMillis(1));

        ModelInvocationException exception = assertThrows(ModelInvocationException.class,
                () -> adapter.rerank("查询",
                        List.of(new RerankModelPort.RerankDocument("chunk-1", "正文"))));

        assertEquals("RERANK_RESULT_INVALID", exception.errorCode());
        assertEquals(1, calls.get());
    }

    /** 创建只包含一个合法评分项的 SDK 响应。 */
    private TextReRankResult successfulResult() {
        TextReRankOutput.Result item = new TextReRankOutput.Result();
        item.setIndex(0);
        item.setRelevanceScore(0.9);
        TextReRankOutput output = new TextReRankOutput();
        output.setResults(List.of(item));
        TextReRankResult result = mock(TextReRankResult.class);
        when(result.getStatusCode()).thenReturn(200);
        when(result.getOutput()).thenReturn(output);
        return result;
    }
}
