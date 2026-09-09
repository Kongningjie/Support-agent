package com.lawrence.supportagent.evaluation;

import java.util.List;

/**
 * 一组毫秒耗时的固定统计摘要。
 *
 * @param sampleCount 样本数量
 * @param averageMs 算术平均耗时毫秒
 * @param p95Ms 使用 nearest-rank 方法计算的 P95 毫秒
 * @param maximumMs 最大耗时毫秒
 */
public record LatencySummary(int sampleCount, double averageMs, long p95Ms, long maximumMs) {
    /** 从非负毫秒样本生成确定性统计摘要。 */
    public static LatencySummary from(List<Long> samples) {
        if (samples == null || samples.isEmpty()) return new LatencySummary(0, 0, 0, 0);
        List<Long> sorted = samples.stream().map(value -> Math.max(0, value)).sorted().toList();
        int rank = Math.max(1, (int) Math.ceil(sorted.size() * 0.95));
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return new LatencySummary(sorted.size(), average, sorted.get(rank - 1), sorted.getLast());
    }
}
