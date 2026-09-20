package com.lawrence.supportagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.auth.MySqlUserRepository;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.DocumentInputType;
import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.mapper.AsyncTaskWorkflowMapper;
import com.lawrence.supportagent.persistence.mapper.UserAccountMapper;
import com.lawrence.supportagent.persistence.record.AsyncTaskMetricsDO;
import com.lawrence.supportagent.persistence.repository.TicketMyBatisRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketCommandUseCase;
import com.lawrence.supportagent.ticket.TicketDetails;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import com.lawrence.supportagent.user.UserRole;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserStatus;
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
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(
            UUID.fromString("20000000-0000-0000-0000-000000000001"), "tester", UserRole.USER);

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
    private AsyncTaskWorkflowMapper taskWorkflowMapper;
    @Autowired
    private IdempotentExecutor idempotentExecutor;
    @Autowired
    private UserAccountMapper userAccountMapper;

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
        Ticket inserted = ticketRepository.save(Ticket.draft(conversationId, turnId, ACTOR.userId(),
                "服务无法启动", "启动时报配置错误", "已检查环境变量", "tester", NOW));

        assertNotNull(inserted.id());
        assertEquals(conversationId, ticketRepository.findById(inserted.id()).orElseThrow().conversationId());

        Ticket updated = ticketRepository.save(inserted.submit("reviewer", NOW.plusSeconds(1)));
        assertEquals(1, updated.version());
        assertEquals(updated, ticketRepository.findById(updated.id()).orElseThrow());
    }

    /** 普通用户只能访问自己的工单，管理员可访问其他用户和历史无归属工单。 */
    @Test
    void shouldScopeTicketsByOwnerAndAllowAdministratorHistoricalAccess() {
        UUID firstOwner = ACTOR.userId();
        UUID secondOwner = UUID.fromString("30000000-0000-0000-0000-000000000001");
        Ticket owned = ticketRepository.assignNumber(ticketRepository.save(Ticket.draft(null, null,
                firstOwner, "归属工单", "仅所有者可见", null, firstOwner.toString(), NOW)),
                "T800000000001");
        Ticket historical = ticketRepository.assignNumber(ticketRepository.save(Ticket.draft(null, null,
                null, "历史工单", "仅管理员可见", null, "legacy", NOW)),
                "T800000000002");

        assertTrue(ticketRepository.findByTicketNoForAccess(
                owned.ticketNo(), firstOwner, false).isPresent());
        assertTrue(ticketRepository.findByTicketNoForAccess(
                owned.ticketNo(), secondOwner, false).isEmpty());
        assertTrue(ticketRepository.findByTicketNoForAccess(
                owned.ticketNo(), secondOwner, true).isPresent());
        assertTrue(ticketRepository.findByTicketNoForAccess(
                historical.ticketNo(), firstOwner, false).isEmpty());
        assertTrue(ticketRepository.findByTicketNoForAccess(
                historical.ticketNo(), firstOwner, true).isPresent());
    }

    /** 验证本地用户 UUID、BCrypt 哈希、角色、状态和乐观锁版本往返。 */
    @Test
    void shouldRoundTripLocalUserWithoutPlaintextPassword() {
        MySqlUserRepository repository = new MySqlUserRepository(userAccountMapper);
        UUID userId = UUID.randomUUID();
        String hash = "$2a$10$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu";
        UserAccount inserted = repository.save(UserAccount.create(userId, "local-user", "本地用户",
                hash, UserRole.USER, ACTOR.userId().toString(), NOW));

        UserAccount loaded = repository.findByUsername("local-user").orElseThrow();
        assertEquals(userId, loaded.userId());
        assertEquals(hash, loaded.passwordHash());
        assertTrue(!loaded.passwordHash().contains("strong-password"));

        UserAccount disabled = repository.save(inserted.changeStatus(
                UserStatus.DISABLED, ACTOR.userId().toString(), NOW.plusSeconds(1)));
        assertEquals(1, disabled.version());
        assertEquals(UserStatus.DISABLED, disabled.status());
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
        Ticket source = ticketRepository.save(Ticket.draft(null, null, ACTOR.userId(), "来源工单",
                "问题描述", null, "tester", NOW));
        ResolvedCase draft = new ResolvedCase(null, source.id(), "案例标题", "问题",
                "根因", "方案", ResolvedCaseStatus.DRAFT,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                0, null, null, null, false, null, null, "tester", NOW,
                "tester", NOW, null, null, null, null);
        ResolvedCase inserted = caseRepository.save(draft);

        assertNotNull(inserted.id());
        assertEquals(inserted, caseRepository.findById(inserted.id()).orElseThrow());
        assertEquals(inserted, caseRepository.findBySourceTicketId(source.id()).orElseThrow());
        assertTrue(caseRepository.findPage(ResolvedCaseStatus.DRAFT, null, "案例", 0, 20)
                .contains(inserted));
        assertThrows(RuntimeException.class, () -> caseRepository.save(ResolvedCase.draft(
                source.id(), "重复案例", "问题", "根因", "方案",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "tester", NOW)));
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
                queryUseCase, idempotentExecutor, Instant::now,
                new AsyncTaskCreator(taskRepository, Instant::now));
        String key = "integration-ticket-" + UUID.randomUUID();

        TicketDetails first = commandUseCase.createDraft(ACTOR, "幂等工单", "相同请求只创建一次",
                null, key);
        TicketDetails replayed = commandUseCase.createDraft(ACTOR, "幂等工单", "相同请求只创建一次",
                null, key);
        ApplicationException conflict = assertThrows(ApplicationException.class,
                () -> commandUseCase.createDraft(ACTOR, "变更标题", "相同请求只创建一次", null, key));

        assertEquals(first.ticketNo(), replayed.ticketNo());
        assertEquals(ErrorCode.COMMON_IDEMPOTENCY_KEY_REUSED, conflict.errorCode());
        assertEquals(1, ticketRepository.count(null, first.ticketNo(), ACTOR.userId(), false));
    }

    /** 验证解决工单和案例生成任务在同一幂等事务中持久化。 */
    @Test
    void shouldResolveTicketAndInsertCaseGenerationTaskAtomically() {
        TicketQueryUseCase queryUseCase = new TicketQueryUseCase(ticketRepository);
        TicketCommandUseCase commandUseCase = new TicketCommandUseCase(ticketRepository,
                queryUseCase, idempotentExecutor, Instant::now,
                new AsyncTaskCreator(taskRepository, Instant::now));
        String suffix = UUID.randomUUID().toString();
        TicketDetails draft = commandUseCase.createDraft(ACTOR, "待解决工单", "连接失败", null,
                "create-resolve-" + suffix);
        TicketDetails open = commandUseCase.submit(ACTOR, draft.ticketNo(), draft.version(),
                "submit-resolve-" + suffix);

        TicketDetails resolved = commandUseCase.resolve(ACTOR, open.ticketNo(), "端口错误",
                "修正端口", open.version(), "resolve-" + suffix);

        assertEquals(com.lawrence.supportagent.ticket.TicketStatus.RESOLVED, resolved.status());
        assertEquals(1, taskRepository.count(AsyncTaskType.CASE_GENERATION,
                AsyncTaskStatus.PENDING, AggregateType.TICKET, null));
    }

    /** 验证案例任务插入失败时解决状态随同一业务事务回滚。 */
    @Test
    void shouldRollbackTicketResolutionWhenCaseTaskInsertFails() {
        TicketQueryUseCase queryUseCase = new TicketQueryUseCase(ticketRepository);
        AsyncTaskCreator failingCreator = new AsyncTaskCreator(taskRepository, Instant::now) {
            /** 模拟数据库无法插入案例生成任务。 */
            @Override
            public AsyncTask create(AsyncTaskType taskType, AggregateType aggregateType,
                                    long aggregateId, long aggregateVersion,
                                    String internalIdempotencyKey, String createdBy) {
                throw new IllegalStateException("模拟任务插入失败");
            }
        };
        TicketCommandUseCase commandUseCase = new TicketCommandUseCase(ticketRepository,
                queryUseCase, idempotentExecutor, Instant::now, failingCreator);
        String suffix = UUID.randomUUID().toString();
        TicketDetails draft = commandUseCase.createDraft(ACTOR, "事务回滚工单", "连接失败", null,
                "create-rollback-" + suffix);
        TicketDetails open = commandUseCase.submit(ACTOR, draft.ticketNo(), draft.version(),
                "submit-rollback-" + suffix);
        long tasksBefore = taskRepository.count(AsyncTaskType.CASE_GENERATION,
                AsyncTaskStatus.PENDING, AggregateType.TICKET, null);

        assertThrows(IllegalStateException.class, () -> commandUseCase.resolve(ACTOR, open.ticketNo(),
                "端口错误", "修正端口", open.version(), "resolve-rollback-" + suffix));

        assertEquals(com.lawrence.supportagent.ticket.TicketStatus.OPEN,
                ticketRepository.findByTicketNo(open.ticketNo()).orElseThrow().status());
        assertEquals(tasksBefore, taskRepository.count(AsyncTaskType.CASE_GENERATION,
                AsyncTaskStatus.PENDING, AggregateType.TICKET, null));
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

    /** 验证 Outbox 指标 SQL 准确汇总积压、额外尝试、死亡任务和分钟吞吐。 */
    @Test
    void shouldAggregateOutboxOperationalMetrics() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AsyncTaskMetricsDO before = taskWorkflowMapper.metrics(now.minusSeconds(60));
        taskRepository.save(new AsyncTask(null, AsyncTaskType.KNOWLEDGE_INDEX,
                AggregateType.MANAGED_DOCUMENT, 7001L, 1L, "metrics-pending:" + UUID.randomUUID(),
                AsyncTaskStatus.PENDING, 0, 3, now, null, null, null, null,
                null, null, "system", now.minusSeconds(30), null, null, now.minusSeconds(30)));
        taskRepository.save(new AsyncTask(null, AsyncTaskType.KNOWLEDGE_DELETE,
                AggregateType.MANAGED_DOCUMENT, 7002L, 1L, "metrics-dead:" + UUID.randomUUID(),
                AsyncTaskStatus.DEAD, 3, 3, now, null, null, "TEST_DEAD", "测试死亡任务",
                null, null, "system", now.minusSeconds(20), now.minusSeconds(19), now, now));
        taskRepository.save(new AsyncTask(null, AsyncTaskType.KNOWLEDGE_DELETE,
                AggregateType.MANAGED_DOCUMENT, 7003L, 1L, "metrics-success:" + UUID.randomUUID(),
                AsyncTaskStatus.SUCCEEDED, 1, 3, now, null, null, null, null,
                null, null, "system", now.minusSeconds(10), now.minusSeconds(9), now, now));

        AsyncTaskMetricsDO after = taskWorkflowMapper.metrics(now.minusSeconds(60));

        assertEquals(before.backlog + 1, after.backlog);
        assertEquals(before.retryAttempts + 2, after.retryAttempts);
        assertEquals(before.dead + 1, after.dead);
        assertEquals(before.throughput + 1, after.throughput);
        assertNotNull(after.oldestCreatedAt);
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
