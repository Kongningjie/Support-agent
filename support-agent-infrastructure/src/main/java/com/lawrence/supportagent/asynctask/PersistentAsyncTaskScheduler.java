package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每两秒以短事务抢占任务，并限制单实例并发和待执行批次容量。 */
@Component
public class PersistentAsyncTaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentAsyncTaskScheduler.class);
    private final AsyncTaskRepository repository;
    private final AsyncTaskRunner runner;
    private final TimeProvider timeProvider;
    private final boolean enabled;
    private final int batchSize;
    private final Duration leaseDuration;
    private final String workerId;
    private final Semaphore capacity;
    private final ExecutorService executor;

    /** 注入调度依赖，并按配置创建固定并发执行器和唯一 Worker 标识。 */
    public PersistentAsyncTaskScheduler(AsyncTaskRepository repository, AsyncTaskRunner runner,
                                        TimeProvider timeProvider, UuidGenerator uuidGenerator,
                                        @Value("${support-agent.async-task.enabled:true}") boolean enabled,
                                        @Value("${support-agent.async-task.worker-id:}") String configuredWorkerId,
                                        @Value("${support-agent.async-task.batch-size:10}") int batchSize,
                                        @Value("${support-agent.async-task.concurrency:2}") int concurrency,
                                        @Value("${support-agent.async-task.lease-duration:5m}")
                                        Duration leaseDuration) {
        this.repository = repository;
        this.runner = runner;
        this.timeProvider = timeProvider;
        validate(batchSize, concurrency, leaseDuration);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "worker-" + uuidGenerator.generate() : configuredWorkerId.trim();
        this.capacity = new Semaphore(batchSize);
        this.executor = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("async-task-", 1).factory());
    }

    /** 按固定延迟抢占不超过当前批次剩余容量的到期任务。 */
    @Scheduled(fixedDelayString = "${support-agent.async-task.poll-delay:2s}")
    public void poll() {
        if (!enabled) {
            return;
        }
        int available = capacity.availablePermits();
        if (available == 0) {
            return;
        }
        Instant now = timeProvider.now();
        List<AsyncTask> tasks = repository.claimDue(workerId, now,
                now.plus(leaseDuration), Math.min(batchSize, available));
        for (AsyncTask task : tasks) {
            capacity.acquireUninterruptibly();
            executor.execute(() -> execute(task));
        }
    }

    /** 在后台线程执行一个任务，并确保释放批次容量。 */
    private void execute(AsyncTask task) {
        try {
            runner.run(task, workerId);
        } catch (RuntimeException exception) {
            LOGGER.error("异步任务结果保存失败，taskId={}，workerId={}",
                    task.id(), workerId, exception);
        } finally {
            capacity.release();
        }
    }

    /** 应用关闭时停止接收新任务并中断仍在执行的本地线程。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 校验调度参数与阶段 2 的安全范围一致。 */
    private void validate(int batchSize, int concurrency, Duration leaseDuration) {
        if (batchSize < 1 || batchSize > 100 || concurrency < 1 || concurrency > batchSize
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("异步任务 Worker 配置不合法");
        }
    }
}
