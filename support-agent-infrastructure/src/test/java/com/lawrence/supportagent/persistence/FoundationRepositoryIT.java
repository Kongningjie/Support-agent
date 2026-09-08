package com.lawrence.supportagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.DocumentInputType;
import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.repository.TicketMyBatisRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketCommandUseCase;
import com.lawrence.supportagent.ticket.TicketDetails;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 使用真实 MySQL 验证四类聚合的 MyBatis Repository 往返转换。 */
@Testcontainers
@SpringBootTest(classes = FoundationRepositoryIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
                "mybatis.configuration.map-underscore-to-camel-case=true"
        })
class FoundationRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-03T08:00:00.123456Z")
            .truncatedTo(ChronoUnit.MICROS);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent")
            .withUsername("support_agent")
            .withPassword("test_password");

    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private ManagedDocumentRepository documentRepository;
    @Autowired
    private ResolvedCaseRepository caseRepository;
    @Autowired
    private AsyncTaskRepository taskRepository;
    @Autowired
    private IdempotentExecutor idempotentExecutor;

    /** 把 Testcontainers 连接信息注入 Spring 数据源。 */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 验证工单插入、UUID 映射、状态更新和乐观锁版本往返。 */
    @Test
    void shouldRoundTripTicket() {
        UUID conversationId = UUID.fromString("d57cc8e3-f357-4bc0-a8cf-a5a472127f76");
        UUID turnId = UUID.fromString("c6fd9532-95eb-42cf-8230-4a0b9d686732");
        Ticket inserted = ticketRepository.save(Ticket.draft(conversationId, turnId,
                "服务无法启动", "启动时报配置错误", "已检查环境变量", "tester", NOW));

        assertNotNull(inserted.id());
        assertEquals(conversationId, ticketRepository.findById(inserted.id()).orElseThrow().conversationId());

        Ticket updated = ticketRepository.save(inserted.submit("reviewer", NOW.plusSeconds(1)));
        assertEquals(1, updated.version());
        assertEquals(updated, ticketRepository.findById(updated.id()).orElseThrow());
    }

    /** 验证托管文档插入、发布状态和审计字段往返。 */
    @Test
    void shouldRoundTripManagedDocument() {
        ManagedDocument inserted = documentRepository.save(ManagedDocument.draft(
                "部署说明", DocumentInputType.DIRECT_TEXT, null, "text/plain",
                "部署正文", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "tester", NOW));
        ManagedDocument indexing = documentRepository.save(
                inserted.startIndexing("tester", NOW.plusSeconds(1)));
        ManagedDocument published = documentRepository.save(
                indexing.publish("reviewer", NOW.plusSeconds(2)));

        assertNotNull(published.id());
        assertEquals(published, documentRepository.findById(published.id()).orElseThrow());
    }

    /** 验证数据库生成列唯一约束可阻止并发检查后仍发生的有效内容重复。 */
    @Test
    void shouldRejectDuplicateActiveManagedDocumentContent() {
        String hash = RequestFingerprint.sha256("duplicate-" + UUID.randomUUID());
        documentRepository.save(ManagedDocument.draft("第一份", DocumentInputType.DIRECT_TEXT,
                null, "text/plain", "相同正文", hash, "tester", NOW));

        ApplicationException conflict = assertThrows(ApplicationException.class,
                () -> documentRepository.save(ManagedDocument.draft("第二份",
                        DocumentInputType.DIRECT_TEXT, null, "text/plain", "相同正文",
                        hash, "tester", NOW.plusSeconds(1))));

        assertEquals(ErrorCode.KNOWLEDGE_DUPLICATE_CONTENT, conflict.errorCode());
    }

    /** 验证已解决案例的外键、发布状态和审计字段往返。 */
    @Test
    void shouldRoundTripResolvedCase() {
        Ticket source = ticketRepository.save(Ticket.draft(null, null, "来源工单",
                "问题描述", null, "tester", NOW));
        ResolvedCase draft = new ResolvedCase(null, source.id(), "案例标题", "问题",
                "根因", "方案", ResolvedCaseStatus.DRAFT,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                0, null, null, null, false, null, null, "tester", NOW,
                "tester", NOW, null, null, null, null);
        ResolvedCase inserted = caseRepository.save(draft);

        assertNotNull(inserted.id());
        assertEquals(inserted, caseRepository.findById(inserted.id()).orElseThrow());
    }

    /** 验证异步任务的调度、租约和执行状态字段往返。 */
    @Test
    void shouldRoundTripAsyncTask() {
        AsyncTask pending = new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 12, 3, "knowledge-index:12:3",
                AsyncTaskStatus.PENDING, 0, 3, NOW, null, null, null, null,
                null, null, "system", NOW, null, null, NOW);
        AsyncTask inserted = taskRepository.save(pending);
        AsyncTask running = taskRepository.save(inserted.start("worker-1", NOW.plusSeconds(1),
                NOW.plusSeconds(301)));

        assertNotNull(running.id());
        assertEquals(running, taskRepository.findById(running.id()).orElseThrow());
    }

    /** 验证工单创建结果可重放，且同一幂等键不能承载不同请求。 */
    @Test
    void shouldReplayIdempotentTicketCreationAndRejectChangedRequest() {
        TicketQueryUseCase queryUseCase = new TicketQueryUseCase(ticketRepository);
        TicketCommandUseCase commandUseCase = new TicketCommandUseCase(ticketRepository,
                queryUseCase, idempotentExecutor, () -> new com.lawrence.supportagent.sharedkernel.OperatorId("tester"),
                Instant::now);
        String key = "integration-ticket-" + UUID.randomUUID();

        TicketDetails first = commandUseCase.createDraft("幂等工单", "相同请求只创建一次",
                null, key);
        TicketDetails replayed = commandUseCase.createDraft("幂等工单", "相同请求只创建一次",
                null, key);
        ApplicationException conflict = assertThrows(ApplicationException.class,
                () -> commandUseCase.createDraft("变更标题", "相同请求只创建一次", null, key));

        assertEquals(first.ticketNo(), replayed.ticketNo());
        assertEquals(ErrorCode.COMMON_IDEMPOTENCY_KEY_REUSED, conflict.errorCode());
        assertEquals(1, ticketRepository.count(null, first.ticketNo()));
    }

    /** 验证首次请求执行期间，并发相同请求快速返回执行中而不重复执行业务。 */
    @Test
    void shouldRejectConcurrentIdempotentExecutionInProgress() throws Exception {
        String key = "integration-in-progress-" + UUID.randomUUID();
        IdempotencyCommand command = new IdempotencyCommand("tester", "CONCURRENT_TEST", key,
                RequestFingerprint.sha256("same-request"), Duration.ofSeconds(30), Duration.ofDays(1));
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> idempotentExecutor.execute(command, () -> {
                actionStarted.countDown();
                try {
                    if (!releaseAction.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试等待首次请求释放超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试线程被中断", exception);
                }
                return new IdempotentResource<>("TEST_RESOURCE", 1L, "first");
            }, ignored -> "replayed"));
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS));

            ApplicationException conflict = assertThrows(ApplicationException.class,
                    () -> idempotentExecutor.execute(command,
                            () -> new IdempotentResource<>("TEST_RESOURCE", 2L, "second"),
                            ignored -> "replayed"));
            assertEquals(ErrorCode.COMMON_IDEMPOTENCY_IN_PROGRESS, conflict.errorCode());
            releaseAction.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    /** 验证多个 Worker 并发抢占时目标任务不重复，并能回收崩溃 Worker 的过期租约。 */
    @Test
    void shouldClaimWithoutDuplicatesAndRecoverExpiredLease() throws Exception {
        Instant dueAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        Set<Long> targetIds = new HashSet<>();
        for (int index = 0; index < 12; index++) {
            AsyncTask saved = taskRepository.save(AsyncTask.pending(AsyncTaskType.KNOWLEDGE_INDEX,
                    AggregateType.MANAGED_DOCUMENT, 1000L + index, 1L,
                    "claim-it:" + UUID.randomUUID(), "system", dueAt));
            targetIds.add(saved.id());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<AsyncTask>> first = executor.submit(() -> claimAfter(start, "worker-a", dueAt));
            Future<List<AsyncTask>> second = executor.submit(() -> claimAfter(start, "worker-b", dueAt));
            start.countDown();
            List<AsyncTask> claimed = new java.util.ArrayList<>(first.get(10, TimeUnit.SECONDS));
            claimed.addAll(second.get(10, TimeUnit.SECONDS));
            List<Long> claimedTargets = claimed.stream().map(AsyncTask::id)
                    .filter(targetIds::contains).toList();
            assertEquals(12, claimedTargets.size());
            assertEquals(12, new HashSet<>(claimedTargets).size());

            long expiredId = claimedTargets.getFirst();
            AsyncTask recovered = taskRepository.claimDue("worker-recovery",
                            dueAt.plusSeconds(301), dueAt.plusSeconds(601), 100).stream()
                    .filter(task -> task.id().equals(expiredId)).findFirst().orElseThrow();
            assertEquals("worker-recovery", recovered.lockedBy());
            assertEquals(2, recovered.attemptCount());

            AsyncTask exhausted = taskRepository.save(new AsyncTask(null,
                    AsyncTaskType.KNOWLEDGE_DELETE, AggregateType.MANAGED_DOCUMENT,
                    5000L, 1L, "exhausted-it:" + UUID.randomUUID(),
                    AsyncTaskStatus.PENDING, 0, 1, dueAt.plusSeconds(1000),
                    null, null, null, null, null, null, "system",
                    dueAt, null, null, dueAt));
            AsyncTask claimedOnce = taskRepository.claimDue("worker-crashed",
                            dueAt.plusSeconds(1000), dueAt.plusSeconds(1300), 100).stream()
                    .filter(task -> task.id().equals(exhausted.id())).findFirst().orElseThrow();
            AsyncTask reclaimed = taskRepository.claimDue("worker-cleanup", dueAt.plusSeconds(1301),
                            dueAt.plusSeconds(1601), 100).stream()
                    .filter(task -> task.id().equals(claimedOnce.id())).findFirst().orElseThrow();
            assertEquals(AsyncTaskStatus.RUNNING, reclaimed.status());
            assertEquals(1, reclaimed.attemptCount());
            assertEquals("ASYNC_TASK_WORKER_LEASE_EXPIRED", reclaimed.lastErrorCode());
        } finally {
            executor.shutdownNow();
        }
    }

    /** 等待并发起点后用指定 Worker 抢占一批到期任务。 */
    private List<AsyncTask> claimAfter(CountDownLatch start, String workerId, Instant now)
            throws InterruptedException {
        start.await();
        return taskRepository.claimDue(workerId, now, now.plusSeconds(300), 100);
    }

    /** 只装配数据源、Flyway、MyBatis Mapper 和 Repository 的测试应用。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = FoundationMapper.class)
    @ComponentScan(basePackages = {
            "com.lawrence.supportagent.persistence.repository",
            "com.lawrence.supportagent.idempotency"
    })
    static class TestApplication {
        /** 为幂等适配器提供与生产一致的 UTC 系统时间端口。 */
        @Bean
        TimeProvider timeProvider() {
            return Instant::now;
        }
    }
}
