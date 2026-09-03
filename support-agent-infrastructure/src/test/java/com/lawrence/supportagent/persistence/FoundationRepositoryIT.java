package com.lawrence.supportagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskStatus;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.knowledge.DocumentInputType;
import com.lawrence.supportagent.knowledge.ManagedDocument;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.persistence.mapper.FoundationMapper;
import com.lawrence.supportagent.persistence.repository.TicketMyBatisRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
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

    /** 只装配数据源、Flyway、MyBatis Mapper 和 Repository 的测试应用。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = FoundationMapper.class)
    @ComponentScan(basePackageClasses = TicketMyBatisRepository.class)
    static class TestApplication {
    }
}
