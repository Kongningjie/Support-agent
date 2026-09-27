package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Duration;

/** 编排案例人工编辑、拒绝、发布和归档操作。 */
public class ResolvedCaseCommandUseCase {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RETENTION = Duration.ofDays(7);
    private final ResolvedCaseRepository repository;
    private final ResolvedCaseQueryUseCase queries;
    private final AsyncTaskCreator taskCreator;
    private final IdempotentExecutor idempotency;
    private final OperatorProvider operators;
    private final TimeProvider time;
    private final DocumentContentPolicy contentPolicy;
    private final KnowledgeSpaceAccessService spaceAccess;
    private final TicketRepository tickets;

    /** 注入案例仓储、查询、异步任务、幂等、审计和安全策略。 */
    public ResolvedCaseCommandUseCase(ResolvedCaseRepository repository,
                                      ResolvedCaseQueryUseCase queries,
                                      AsyncTaskCreator taskCreator,
                                      IdempotentExecutor idempotency,
                                      OperatorProvider operators, TimeProvider time,
                                      DocumentContentPolicy contentPolicy,
                                      KnowledgeSpaceAccessService spaceAccess,
                                      TicketRepository tickets) {
        this.repository = repository;
        this.queries = queries;
        this.taskCreator = taskCreator;
        this.idempotency = idempotency;
        this.operators = operators;
        this.time = time;
        this.contentPolicy = contentPolicy;
        this.spaceAccess = spaceAccess;
        this.tickets = tickets;
    }

    /** 人工修改草稿或发布失败案例，并清理旧发布失败信息。 */
    public ResolvedCaseDetails revise(AuthenticatedUser actor, long caseId, String title, String problem,
                                       String cause, String solution, long version) {
        ResolvedCase current = requireVersion(queries.requireCase(caseId), version);
        spaceAccess.requireRole(actor, current.spaceId(), SpaceRole.EDITOR);
        String safeTitle = required(title, "案例标题", 160);
        String safeProblem = required(problem, "问题描述", 4000);
        String safeCause = required(cause, "根因", 4000);
        String safeSolution = required(solution, "解决方案", 8000);
        ResolvedCase revised = current.revise(safeTitle, safeProblem, safeCause, safeSolution,
                contentHash(safeTitle, safeProblem, safeCause, safeSolution),
                operators.currentOperator().value(), time.now());
        repository.save(revised);
        return queries.get(actor, caseId);
    }

    /** 人工确认后幂等发起案例知识索引任务。 */
    public ResolvedCaseDetails publish(AuthenticatedUser actor, long caseId, long version,
                                       String idempotencyKey) {
        requireRole(actor, caseId, SpaceRole.MANAGER);
        return action(actor, "RESOLVED_CASE_PUBLISH", caseId, version, null, idempotencyKey, current -> {
            requireSameSpaceAsTicket(current);
            contentPolicy.verifyNoSensitiveContent(String.join("\n", current.title(), current.problem(),
                    current.cause(), current.solution()));
            ResolvedCase publishing = repository.save(current.startPublishing(
                    operators.currentOperator().value(), time.now()));
            taskCreator.create(AsyncTaskType.KNOWLEDGE_INDEX, AggregateType.RESOLVED_CASE,
                    publishing.id(), publishing.version(),
                    "case-index:" + publishing.id() + ":" + publishing.version(),
                    operators.currentOperator().value());
            return publishing;
        });
    }

    /** 人工确认案例不适合进入知识库后永久拒绝。 */
    public ResolvedCaseDetails reject(AuthenticatedUser actor, long caseId, String reason, long version,
                                      String idempotencyKey) {
        requireRole(actor, caseId, SpaceRole.MANAGER);
        String normalized = required(reason, "拒绝原因", 500);
        return action(actor, "RESOLVED_CASE_REJECT", caseId, version, normalized,
                idempotencyKey, current -> repository.save(current.reject(normalized,
                        operators.currentOperator().value(), time.now())));
    }

    /** 归档已发布案例并异步删除其全部索引分块。 */
    public ResolvedCaseDetails archive(AuthenticatedUser actor, long caseId, String reason, long version,
                                       String idempotencyKey) {
        requireRole(actor, caseId, SpaceRole.MANAGER);
        String normalized = required(reason, "归档原因", 500);
        return action(actor, "RESOLVED_CASE_ARCHIVE", caseId, version, normalized,
                idempotencyKey, current -> {
                    ResolvedCase archived = repository.save(current.archive(normalized,
                            operators.currentOperator().value(), time.now()));
                    taskCreator.create(AsyncTaskType.KNOWLEDGE_DELETE, AggregateType.RESOLVED_CASE,
                            archived.id(), archived.version(),
                            "case-delete:" + archived.id() + ":" + archived.version(),
                            operators.currentOperator().value());
                    return archived;
                });
    }

    /** 在统一幂等事务内执行一个案例状态动作并支持结果重放。 */
    private ResolvedCaseDetails action(AuthenticatedUser actor, String operation, long caseId,
                                       long version, String reason,
                                       String key, java.util.function.Function<ResolvedCase,
                                       ResolvedCase> mutation) {
        String normalizedKey = required(key, "幂等键", 160);
        IdempotencyCommand command = new IdempotencyCommand(operators.currentOperator().value(),
                operation, normalizedKey, RequestFingerprint.sha256(Long.toString(caseId),
                Long.toString(version), reason), LEASE, RETENTION);
        return idempotency.execute(command, () -> {
            ResolvedCase saved = mutation.apply(requireVersion(queries.requireCase(caseId), version));
            return new IdempotentResource<>("RESOLVED_CASE", saved.id(), queries.get(actor, saved.id()));
        }, id -> queries.get(actor, id));
    }

    /** 要求当前主体具有案例空间角色并返回当前案例。 */
    private ResolvedCase requireRole(AuthenticatedUser actor, long caseId, SpaceRole role) {
        ResolvedCase value = queries.requireCase(caseId);
        spaceAccess.requireRole(actor, value.spaceId(), role);
        return value;
    }

    /** 发布前确认案例仍与来源工单处于同一知识空间。 */
    private void requireSameSpaceAsTicket(ResolvedCase value) {
        var ticket = tickets.findById(value.sourceTicketId()).orElseThrow(() ->
                new IllegalStateException("案例来源工单不存在"));
        if (!value.spaceId().equals(ticket.spaceId())) {
            throw new ApplicationException(ErrorCode.KNOWLEDGE_STATUS_CONFLICT,
                    "案例与来源工单的知识空间不一致");
        }
    }

    /** 校验请求版本与当前案例一致。 */
    private ResolvedCase requireVersion(ResolvedCase value, long version) {
        if (version < 0 || value.version() != version) {
            throw new ApplicationException(ErrorCode.COMMON_CONFLICT, "案例版本已变化");
        }
        return value;
    }

    /** 校验并规整案例必填文本。 */
    private String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    /** 计算案例四个可发布字段的稳定内容哈希。 */
    private String contentHash(String title, String problem, String cause, String solution) {
        return RequestFingerprint.sha256(title, problem, cause, solution);
    }
}
