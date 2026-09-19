package com.lawrence.supportagent.config;

import com.lawrence.supportagent.persistence.mapper.AsyncTaskWorkflowMapper;
import com.lawrence.supportagent.persistence.record.AsyncTaskMetricsDO;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期采样 MySQL Outbox 状态并发布低基数 Micrometer 指标。 */
@Component
public class OutboxMetricsCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxMetricsCollector.class);
    private static final Duration THROUGHPUT_WINDOW = Duration.ofMinutes(1);
    private final AsyncTaskWorkflowMapper mapper;
    private final TimeProvider timeProvider;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestWaitSeconds = new AtomicLong();
    private final AtomicLong retryAttempts = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong throughput = new AtomicLong();

    /** 注入任务查询、统一时钟和指标注册表并注册五项稳定指标。 */
    public OutboxMetricsCollector(AsyncTaskWorkflowMapper mapper, TimeProvider timeProvider,
                                  MeterRegistry registry) {
        this.mapper = mapper;
        this.timeProvider = timeProvider;
        register(registry, "support.agent.outbox.backlog", backlog,
                "尚未进入终态的 Outbox 任务数量");
        register(registry, "support.agent.outbox.oldest.wait.seconds", oldestWaitSeconds,
                "最老未终态 Outbox 任务的等待秒数");
        register(registry, "support.agent.outbox.retry.attempts", retryAttempts,
                "当前任务表累计额外尝试次数");
        register(registry, "support.agent.outbox.dead", dead,
                "当前处于 DEAD 状态的 Outbox 任务数量");
        register(registry, "support.agent.outbox.throughput.per.minute", throughput,
                "最近一分钟成功完成的 Outbox 任务数量");
    }

    /** 按配置周期刷新指标；采样失败只保留上次结果，不影响业务请求。 */
    @Scheduled(fixedDelayString = "${support-agent.observability.outbox-sample-delay:10s}")
    public void sample() {
        Instant now = timeProvider.now();
        try {
            AsyncTaskMetricsDO metrics = mapper.metrics(now.minus(THROUGHPUT_WINDOW));
            update(metrics, now);
        } catch (RuntimeException exception) {
            LOGGER.warn("Outbox 指标采样失败，将保留上次成功结果：{}",
                    exception.getClass().getSimpleName());
        }
    }

    /** 把数据库快照原子地写入各项 Gauge 的本地数值。 */
    private void update(AsyncTaskMetricsDO metrics, Instant now) {
        if (metrics == null) {
            return;
        }
        backlog.set(Math.max(0, metrics.backlog));
        retryAttempts.set(Math.max(0, metrics.retryAttempts));
        dead.set(Math.max(0, metrics.dead));
        throughput.set(Math.max(0, metrics.throughput));
        long wait = metrics.oldestCreatedAt == null ? 0
                : Math.max(0, Duration.between(metrics.oldestCreatedAt, now).toSeconds());
        oldestWaitSeconds.set(wait);
    }

    /** 注册读取 AtomicLong 的 Gauge，避免采集线程在抓取时访问数据库。 */
    private void register(MeterRegistry registry, String name, AtomicLong value,
                          String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .register(registry);
    }
}
