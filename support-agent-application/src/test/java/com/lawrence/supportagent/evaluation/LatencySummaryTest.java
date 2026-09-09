package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证阶段六耗时摘要的确定性统计口径。 */
class LatencySummaryTest {
    /** 验证负数归零、平均值、nearest-rank P95 和最大值。 */
    @Test
    void shouldSummarizeLatencyUsingNearestRank() {
        LatencySummary summary = LatencySummary.from(List.of(-5L, 10L, 20L, 30L, 40L));

        assertEquals(5, summary.sampleCount());
        assertEquals(20.0, summary.averageMs());
        assertEquals(40, summary.p95Ms());
        assertEquals(40, summary.maximumMs());
    }

    /** 验证空样本返回全零摘要。 */
    @Test
    void shouldReturnZeroSummaryForEmptySamples() {
        assertEquals(new LatencySummary(0, 0, 0, 0), LatencySummary.from(List.of()));
    }
}
