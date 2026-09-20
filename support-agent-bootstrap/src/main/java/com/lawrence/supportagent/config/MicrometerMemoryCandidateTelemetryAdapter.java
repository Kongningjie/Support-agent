package com.lawrence.supportagent.config;

import com.lawrence.supportagent.memory.CandidateInsertOutcome;
import com.lawrence.supportagent.memory.port.MemoryCandidateTelemetryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用 Micrometer 发布不含用户、会话和正文的长期记忆候选低基数指标。 */
public class MicrometerMemoryCandidateTelemetryAdapter implements MemoryCandidateTelemetryPort {
    private final MeterRegistry registry;
    private final AtomicInteger inFlight = new AtomicInteger();

    /** 注入统一指标注册表并注册当前全局执行数 Gauge。 */
    public MicrometerMemoryCandidateTelemetryAdapter(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("support.agent.memory.candidate.inflight", inFlight, AtomicInteger::get)
                .description("当前单实例持有全局许可的长期记忆候选执行数")
                .register(registry);
    }

    /** {@inheritDoc} */
    @Override public void recordRequest() {
        counter("support.agent.memory.candidate.requests", "长期记忆候选请求数").increment();
    }

    /** {@inheritDoc} */
    @Override public void recordOutcome(CandidateOutcome outcome) {
        counter("support.agent.memory.candidate.outcomes", "长期记忆候选正常结果数",
                "outcome", outcome.name()).increment();
    }

    /** {@inheritDoc} */
    @Override public void recordRejection(CandidateRejectionReason reason) {
        counter("support.agent.memory.candidate.rejections", "长期记忆候选并发拒绝数",
                "reason", reason.name()).increment();
    }

    /** {@inheritDoc} */
    @Override public void recordFailure(CandidateFailureReason reason) {
        counter("support.agent.memory.candidate.failures", "长期记忆候选隔离失败数",
                "reason", reason.name()).increment();
    }

    /** {@inheritDoc} */
    @Override public void recordInsertion(CandidateInsertOutcome outcome) {
        counter("support.agent.memory.candidate.insertions", "长期记忆候选写入结果数",
                "outcome", outcome.name()).increment();
    }

    /** {@inheritDoc} */
    @Override public void executionStarted() {
        inFlight.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override public void executionFinished() {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** {@inheritDoc} */
    @Override public void recordDuration(long durationMs) {
        Timer.builder("support.agent.memory.candidate.duration")
                .description("长期记忆候选取得许可到释放许可的耗时")
                .publishPercentiles(0.95).register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Override public void recordCleanup(int deletedCount, long durationMs, boolean succeeded) {
        counter("support.agent.memory.cleanup.runs", "长期记忆候选清理触发数",
                "outcome", succeeded ? "SUCCESS" : "FAILURE").increment();
        DistributionSummary.builder("support.agent.memory.cleanup.deleted")
                .description("单次长期记忆候选清理删除数量")
                .register(registry).record(Math.max(0, deletedCount));
        Timer.builder("support.agent.memory.cleanup.duration")
                .description("长期记忆候选清理耗时")
                .tag("outcome", succeeded ? "SUCCESS" : "FAILURE")
                .publishPercentiles(0.95).register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    /** 创建或复用无标签 Counter。 */
    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    /** 创建或复用只带一个冻结低基数标签的 Counter。 */
    private Counter counter(String name, String description, String tagName, String tagValue) {
        return Counter.builder(name).description(description).tag(tagName, tagValue)
                .register(registry);
    }
}
