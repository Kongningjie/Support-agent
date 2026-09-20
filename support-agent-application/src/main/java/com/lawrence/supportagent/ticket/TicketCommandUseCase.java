package com.lawrence.supportagent.ticket;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 编排手工工单草稿、修改、提交和关闭，并落实幂等与乐观锁边界。 */
public class TicketCommandUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final TicketRepository repository;
    private final TicketQueryUseCase queryUseCase;
    private final IdempotentExecutor idempotentExecutor;
    private final TimeProvider timeProvider;
    private final AsyncTaskCreator taskCreator;

    /** 注入工单持久化、查询、幂等、操作者和时间端口。 */
    public TicketCommandUseCase(TicketRepository repository, TicketQueryUseCase queryUseCase,
                                IdempotentExecutor idempotentExecutor, TimeProvider timeProvider,
                                AsyncTaskCreator taskCreator) {
        this.repository = repository;
        this.queryUseCase = queryUseCase;
        this.idempotentExecutor = idempotentExecutor;
        this.timeProvider = timeProvider;
        this.taskCreator = taskCreator;
    }

    /** 手工创建工单草稿，并由内部主键确定性生成对外编号。 */
    public TicketDetails createDraft(AuthenticatedUser actor, String title, String problemDescription,
                                     String attemptedActions, String idempotencyKey) {
        requireActor(actor);
        String normalizedTitle = required(title, "工单标题", 160);
        String normalizedProblem = required(problemDescription, "问题描述", 8000);
        String normalizedActions = optional(attemptedActions, "已尝试操作", 8000);
        IdempotencyCommand command = command(actor, "TICKET_CREATE_DRAFT", idempotencyKey,
                RequestFingerprint.sha256(normalizedTitle, normalizedProblem, normalizedActions));
        return idempotentExecutor.execute(command, () -> {
            Instant now = timeProvider.now();
            String operator = actor.userId().toString();
            Ticket inserted = repository.save(Ticket.draft(null, null, actor.userId(), normalizedTitle,
                    normalizedProblem, normalizedActions, operator, now));
            String ticketNo = formatTicketNo(inserted.id());
            Ticket numbered = repository.assignNumber(inserted, ticketNo);
            return new IdempotentResource<>("TICKET", numbered.id(), TicketDetails.from(numbered));
        }, queryUseCase::getByInternalId);
    }

    /** 使用冻结会话轮次创建唯一工单草稿；模型生成必须在调用本方法前完成。 */
    public TicketDetails createSuggestedDraft(AuthenticatedUser actor, UUID conversationId, UUID sourceTurnId,
                                              String title, String problemDescription,
                                              String attemptedActions, String idempotencyKey) {
        requireActor(actor);
        if (conversationId == null || sourceTurnId == null) throw new IllegalArgumentException("会话和来源轮次不能为空");
        String normalizedTitle = required(title, "工单标题", 160);
        String normalizedProblem = required(problemDescription, "问题描述", 8000);
        String normalizedActions = optional(attemptedActions, "已尝试操作", 8000);
        IdempotencyCommand command = command(actor, "TICKET_CREATE_FROM_CONVERSATION", idempotencyKey,
                RequestFingerprint.sha256(conversationId.toString(), sourceTurnId.toString()));
        return idempotentExecutor.execute(command, () -> {
            Instant now = timeProvider.now();
            String operator = actor.userId().toString();
            Ticket inserted = repository.save(Ticket.draft(conversationId, sourceTurnId, actor.userId(),
                    normalizedTitle, normalizedProblem, normalizedActions, operator, now));
            Ticket numbered = repository.assignNumber(inserted, formatTicketNo(inserted.id()));
            return new IdempotentResource<>("TICKET", numbered.id(), TicketDetails.from(numbered));
        }, queryUseCase::getByInternalId);
    }

    /** 修改仍处于草稿状态且版本匹配的工单内容。 */
    public TicketDetails reviseDraft(AuthenticatedUser actor, String ticketNo, String title, String problemDescription,
                                     String attemptedActions, long version) {
        requireActor(actor);
        Ticket current = requireVersion(queryUseCase.requireTicket(actor, ticketNo), version);
        requireStatus(current, TicketStatus.DRAFT, "只有草稿工单可以修改");
        Ticket revised = current.reviseDraft(required(title, "工单标题", 160),
                required(problemDescription, "问题描述", 8000),
                optional(attemptedActions, "已尝试操作", 8000),
                actor.userId().toString(), timeProvider.now());
        return TicketDetails.from(repository.save(revised));
    }

    /** 幂等地把版本匹配的草稿提交为开放工单。 */
    public TicketDetails submit(AuthenticatedUser actor, String ticketNo, long version, String idempotencyKey) {
        requireActor(actor);
        IdempotencyCommand command = command(actor, "TICKET_SUBMIT", idempotencyKey,
                RequestFingerprint.sha256(ticketNo, Long.toString(version)));
        return idempotentExecutor.execute(command, () -> {
            Ticket current = requireVersion(queryUseCase.requireTicket(actor, ticketNo), version);
            requireStatus(current, TicketStatus.DRAFT, "只有草稿工单可以提交");
            Ticket saved = repository.save(current.submit(
                    actor.userId().toString(), timeProvider.now()));
            return new IdempotentResource<>("TICKET", saved.id(), TicketDetails.from(saved));
        }, queryUseCase::getByInternalId);
    }

    /** 幂等地关闭版本匹配的草稿或开放工单。 */
    public TicketDetails close(AuthenticatedUser actor, String ticketNo, String reason, long version, String idempotencyKey) {
        requireActor(actor);
        String normalizedReason = required(reason, "关闭原因", 500);
        IdempotencyCommand command = command(actor, "TICKET_CLOSE", idempotencyKey,
                RequestFingerprint.sha256(ticketNo, Long.toString(version), normalizedReason));
        return idempotentExecutor.execute(command, () -> {
            Ticket current = requireVersion(queryUseCase.requireTicket(actor, ticketNo), version);
            if (current.status() != TicketStatus.DRAFT && current.status() != TicketStatus.OPEN) {
                throw new ApplicationException(ErrorCode.TICKET_STATUS_CONFLICT,
                        "只有草稿或开放工单可以关闭");
            }
            Ticket saved = repository.save(current.close(normalizedReason,
                    actor.userId().toString(), timeProvider.now()));
            return new IdempotentResource<>("TICKET", saved.id(), TicketDetails.from(saved));
        }, queryUseCase::getByInternalId);
    }

    /** 幂等解决开放工单，并在同一事务创建案例生成任务。 */
    public TicketDetails resolve(AuthenticatedUser actor, String ticketNo, String rootCause, String solution,
                                 long version, String idempotencyKey) {
        requireActor(actor);
        String normalizedCause = required(rootCause, "根因", 4000);
        String normalizedSolution = required(solution, "解决方案", 8000);
        IdempotencyCommand command = command(actor, "TICKET_RESOLVE", idempotencyKey,
                RequestFingerprint.sha256(ticketNo, Long.toString(version),
                        normalizedCause, normalizedSolution));
        return idempotentExecutor.execute(command, () -> {
            Ticket current = requireVersion(queryUseCase.requireTicket(actor, ticketNo), version);
            requireStatus(current, TicketStatus.OPEN, "只有开放工单可以解决");
            String operator = actor.userId().toString();
            Ticket saved = repository.save(current.resolve(normalizedCause, normalizedSolution,
                    operator, timeProvider.now()));
            taskCreator.create(AsyncTaskType.CASE_GENERATION, AggregateType.TICKET,
                    saved.id(), saved.version(),
                    "case-generation:" + saved.id() + ":" + saved.version(), operator);
            return new IdempotentResource<>("TICKET", saved.id(), TicketDetails.from(saved));
        }, queryUseCase::getByInternalId);
    }

    /** 构造具有统一租约和七天保留期的幂等命令。 */
    private IdempotencyCommand command(AuthenticatedUser actor, String operationType,
                                       String key, String requestHash) {
        String normalizedKey = required(key, "幂等键", 160);
        return new IdempotencyCommand(actor.userId().toString(), operationType,
                normalizedKey, requestHash, IDEMPOTENCY_LEASE, IDEMPOTENCY_RETENTION);
    }

    /** 拒绝绕过接口安全链直接传入的空认证上下文。 */
    private void requireActor(AuthenticatedUser actor) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
    }

    /** 校验并规整必填文本。 */
    private String required(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    /** 校验并规整可空文本。 */
    private String optional(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, fieldName, maxLength);
    }

    /** 校验客户端版本，避免并发覆盖。 */
    private Ticket requireVersion(Ticket ticket, long version) {
        if (version < 0 || ticket.version() != version) {
            throw new ApplicationException(ErrorCode.TICKET_VERSION_CONFLICT, "工单版本已变化");
        }
        return ticket;
    }

    /** 校验工单状态并转换为稳定应用错误。 */
    private void requireStatus(Ticket ticket, TicketStatus expected, String message) {
        if (ticket.status() != expected) {
            throw new ApplicationException(ErrorCode.TICKET_STATUS_CONFLICT, message);
        }
    }

    /** 按内部主键生成 T 加 12 位十进制数字的对外编号。 */
    private String formatTicketNo(long id) {
        if (id <= 0 || id > 999_999_999_999L) {
            throw new IllegalStateException("工单编号空间已耗尽");
        }
        return "T%012d".formatted(id);
    }
}
