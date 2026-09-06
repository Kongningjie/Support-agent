package com.lawrence.supportagent.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.sharedkernel.OperatorId;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证工单应用用例的编号、幂等、状态、分页和版本边界。 */
class TicketUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-04T01:00:00Z");
    private TicketRepository repository;
    private TicketCommandUseCase commands;
    private TicketQueryUseCase queries;
    private AtomicReference<Ticket> stored;

    /** 为每个测试创建可观察的内存仓储行为和幂等执行器。 */
    @BeforeEach
    void setUp() {
        repository = mock(TicketRepository.class);
        stored = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            Ticket value = invocation.getArgument(0);
            Ticket persisted = value.id() == null ? withId(value, 1L) : value;
            stored.set(persisted);
            return persisted;
        });
        when(repository.assignNumber(any(), any())).thenAnswer(invocation -> {
            Ticket value = invocation.getArgument(0);
            Ticket numbered = value.assignNumber(value.id(), invocation.getArgument(1));
            stored.set(numbered);
            return numbered;
        });
        when(repository.findById(1L)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.findByTicketNo("T000000000001"))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        queries = new TicketQueryUseCase(repository);
        commands = new TicketCommandUseCase(repository, queries, new MemoryIdempotentExecutor(),
                () -> new OperatorId("dev-operator"), () -> NOW);
    }

    /** 验证草稿编号格式及同 Key 同请求复用首次工单。 */
    @Test
    void shouldCreateAndReplayDraft() {
        TicketDetails first = commands.createDraft("启动失败", "无法连接数据库", null, "create-1");
        TicketDetails replay = commands.createDraft("启动失败", "无法连接数据库", null, "create-1");

        assertEquals("T000000000001", first.ticketNo());
        assertEquals(first.ticketNo(), replay.ticketNo());
        assertEquals(TicketStatus.DRAFT, replay.status());
    }

    /** 验证同一幂等 Key 携带不同请求时返回稳定冲突。 */
    @Test
    void shouldRejectReusedIdempotencyKeyWithDifferentRequest() {
        commands.createDraft("启动失败", "无法连接数据库", null, "create-1");

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> commands.createDraft("另一个问题", "无法连接数据库", null, "create-1"));
        assertEquals(ErrorCode.COMMON_IDEMPOTENCY_KEY_REUSED, exception.errorCode());
    }

    /** 验证草稿修改、提交、关闭及版本冲突使用稳定业务错误。 */
    @Test
    void shouldEnforceTicketStateAndVersion() {
        TicketDetails draft = commands.createDraft("启动失败", "无法连接数据库", null, "create-1");
        TicketDetails revised = commands.reviseDraft(draft.ticketNo(), "启动异常",
                "数据库拒绝连接", "检查了端口", draft.version());
        TicketDetails open = commands.submit(revised.ticketNo(), revised.version(), "submit-1");
        TicketDetails closed = commands.close(open.ticketNo(), "用户确认关闭",
                open.version(), "close-1");

        assertEquals(TicketStatus.CLOSED, closed.status());
        assertEquals(3, closed.version());
        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> commands.reviseDraft(closed.ticketNo(), "标题", "描述", null, 0));
        assertEquals(ErrorCode.TICKET_VERSION_CONFLICT, exception.errorCode());
    }

    /** 为测试中的新建聚合补入模拟数据库生成的内部主键。 */
    private Ticket withId(Ticket value, long id) {
        return new Ticket(id, value.ticketNo(), value.conversationId(), value.sourceTurnId(),
                value.title(), value.problemDescription(), value.attemptedActions(), value.status(),
                value.rootCause(), value.solution(), value.closeReason(), value.version(),
                value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt(),
                value.resolvedBy(), value.resolvedAt(), value.closedBy(), value.closedAt());
    }

    /** 仅用于应用单元测试的同步内存幂等执行器。 */
    private static final class MemoryIdempotentExecutor implements IdempotentExecutor {
        private final Map<String, StoredResult> results = new HashMap<>();

        /** {@inheritDoc} */
        @Override
        public <T> T execute(IdempotencyCommand command, Supplier<IdempotentResource<T>> action,
                             LongFunction<T> replayLoader) {
            String key = command.operationType() + ":" + command.idempotencyKey();
            StoredResult existing = results.get(key);
            if (existing != null) {
                if (!existing.requestHash.equals(command.requestHash())) {
                    throw new ApplicationException(ErrorCode.COMMON_IDEMPOTENCY_KEY_REUSED,
                            "同一幂等键不能用于不同请求");
                }
                return replayLoader.apply(existing.resourceId);
            }
            IdempotentResource<T> created = action.get();
            results.put(key, new StoredResult(command.requestHash(), created.resourceId()));
            return created.value();
        }
    }

    /** 保存单元测试重放所需的请求哈希和资源 ID。 */
    private record StoredResult(String requestHash, long resourceId) {
    }
}
