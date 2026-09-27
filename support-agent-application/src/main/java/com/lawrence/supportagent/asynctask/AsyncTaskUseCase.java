package com.lawrence.supportagent.asynctask;

import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.idempotency.IdempotencyCommand;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.idempotency.IdempotentResource;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.ManagedDocumentStatus;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceAccessService;
import com.lawrence.supportagent.knowledgespace.SpaceRole;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.ticket.TicketStatus;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** 提供异步任务运维查询、取消和经过业务有效性检查的人工重试。 */
public class AsyncTaskUseCase {
    private static final Duration IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private final AsyncTaskRepository taskRepository;
    private final TicketRepository ticketRepository;
    private final ManagedDocumentRepository documentRepository;
    private final ResolvedCaseRepository caseRepository;
    private final IdempotentExecutor idempotentExecutor;
    private final OperatorProvider operatorProvider;
    private final TimeProvider timeProvider;
    private final KnowledgeSpaceAccessService spaceAccess;

    /** 注入任务、关联聚合、幂等、操作者和时间端口。 */
    public AsyncTaskUseCase(AsyncTaskRepository taskRepository, TicketRepository ticketRepository,
                            ManagedDocumentRepository documentRepository,
                            ResolvedCaseRepository caseRepository,
                            IdempotentExecutor idempotentExecutor,
                            OperatorProvider operatorProvider, TimeProvider timeProvider,
                            KnowledgeSpaceAccessService spaceAccess) {
        this.taskRepository = taskRepository;
        this.ticketRepository = ticketRepository;
        this.documentRepository = documentRepository;
        this.caseRepository = caseRepository;
        this.idempotentExecutor = idempotentExecutor;
        this.operatorProvider = operatorProvider;
        this.timeProvider = timeProvider;
        this.spaceAccess = spaceAccess;
    }

    /** 按任务 ID 查询隐藏执行锁的运维详情。 */
    public AsyncTaskDetails get(AuthenticatedUser actor, long taskId) {
        AsyncTask task = requireTask(taskId);
        requireVisible(actor, task, false);
        return AsyncTaskDetails.from(task);
    }

    /** 按受控条件和稳定排序返回一页任务。 */
    public AsyncTaskPage page(AuthenticatedUser actor, AsyncTaskType taskType, AsyncTaskStatus status,
                              AggregateType aggregateType, Long aggregateId,
                              int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size
                || aggregateId != null && aggregateId <= 0) {
            throw new IllegalArgumentException("异步任务分页参数不合法");
        }
        int offset = (page - 1) * size;
        List<AsyncTask> visible = visibleTasks(actor, taskType, status, aggregateType, aggregateId);
        long total = visible.size();
        List<AsyncTaskDetails> items = visible.stream().skip(offset).limit(size)
                .map(AsyncTaskDetails::from).toList();
        long pages = total == 0 ? 0 : (total - 1) / size + 1;
        return new AsyncTaskPage(items, page, size, total,
                pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages);
    }

    /** 幂等地为仍适用于原业务版本的死亡任务创建新任务。 */
    public AsyncTaskDetails retry(AuthenticatedUser actor, long taskId, String reason,
                                  String idempotencyKey) {
        String normalizedReason = required(reason, "人工重试原因", 500);
        String normalizedKey = required(idempotencyKey, "幂等键", 160);
        String requestHash = RequestFingerprint.sha256(Long.toString(taskId), normalizedReason);
        IdempotencyCommand command = new IdempotencyCommand(operatorProvider.currentOperator().value(),
                "ASYNC_TASK_RETRY", normalizedKey, requestHash,
                IDEMPOTENCY_LEASE, IDEMPOTENCY_RETENTION);
        return idempotentExecutor.execute(command, () -> {
            AsyncTask original = requireTask(taskId);
            requireVisible(actor, original, true);
            ensureRetryable(original);
            String internalKey = "manual-retry:" + taskId;
            AsyncTask created = taskRepository.save(AsyncTask.manualRetry(original,
                    internalKey, normalizedReason, operatorProvider.currentOperator().value(), timeProvider.now()));
            return new IdempotentResource<>("ASYNC_TASK", created.id(), AsyncTaskDetails.from(created));
        }, id -> replayRetry(actor, id));
    }

    /** 重放幂等人工重试结果前重新校验当前主体仍拥有活动空间管理权限。 */
    private AsyncTaskDetails replayRetry(AuthenticatedUser actor, long taskId) {
        AsyncTask task = requireTask(taskId);
        requireVisible(actor, task, true);
        return AsyncTaskDetails.from(task);
    }

    /** 分批扫描任务并在应用层按关联资源空间和任务类型过滤，避免泄露原始工单事实。 */
    private List<AsyncTask> visibleTasks(AuthenticatedUser actor, AsyncTaskType taskType,
                                         AsyncTaskStatus status, AggregateType aggregateType,
                                         Long aggregateId) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        List<AsyncTask> visible = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<AsyncTask> batch = taskRepository.findPage(taskType, status, aggregateType,
                    aggregateId, offset, 100);
            for (AsyncTask task : batch) {
                if (isVisible(actor, task)) {
                    visible.add(task);
                }
            }
            if (batch.size() < 100) {
                return List.copyOf(visible);
            }
            offset += batch.size();
        }
    }

    /** 返回任务是否可被当前主体查看；非管理员只允许空间知识任务。 */
    private boolean isVisible(AuthenticatedUser actor, AsyncTask task) {
        if (actor.administrator()) {
            return true;
        }
        if (task.taskType() == AsyncTaskType.CASE_GENERATION) {
            return false;
        }
        UUID spaceId = aggregateSpaceId(task);
        if (spaceId == null) {
            return false;
        }
        try {
            spaceAccess.requireRoleForMetadata(actor, spaceId, SpaceRole.MANAGER);
            return true;
        } catch (ApplicationException exception) {
            return false;
        }
    }

    /** 要求当前主体能查看任务，并在重试时重新校验活动空间 MANAGER 权限。 */
    private void requireVisible(AuthenticatedUser actor, AsyncTask task, boolean retry) {
        if (actor == null) {
            throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效或已经过期");
        }
        if (actor.administrator()) {
            return;
        }
        if (task.taskType() == AsyncTaskType.CASE_GENERATION) {
            throw new ApplicationException(ErrorCode.ASYNC_TASK_NOT_FOUND, "异步任务不存在");
        }
        UUID spaceId = aggregateSpaceId(task);
        if (spaceId == null) {
            throw new ApplicationException(ErrorCode.ASYNC_TASK_NOT_FOUND, "异步任务不存在");
        }
        try {
            if (retry && task.taskType() == AsyncTaskType.KNOWLEDGE_INDEX) {
                spaceAccess.requireRole(actor, spaceId, SpaceRole.MANAGER);
            } else {
                spaceAccess.requireRoleForMetadata(actor, spaceId, SpaceRole.MANAGER);
            }
        } catch (ApplicationException exception) {
            throw new ApplicationException(ErrorCode.ASYNC_TASK_NOT_FOUND, "异步任务不存在");
        }
    }

    /** 从任务关联资源的 MySQL 当前事实解析空间，拒绝信任任务载荷。 */
    private UUID aggregateSpaceId(AsyncTask task) {
        if (task.aggregateType() == AggregateType.MANAGED_DOCUMENT) {
            return documentRepository.findById(task.aggregateId())
                    .map(value -> value.spaceId()).orElse(null);
        }
        if (task.aggregateType() == AggregateType.RESOLVED_CASE) {
            return caseRepository.findById(task.aggregateId())
                    .map(value -> value.spaceId()).orElse(null);
        }
        return null;
    }

    /** 供业务编排在关联对象失效时取消尚未完成的任务。 */
    public void cancel(long taskId) {
        if (!taskRepository.cancel(taskId, timeProvider.now())) {
            AsyncTask task = requireTask(taskId);
            if (task.status() == AsyncTaskStatus.SUCCEEDED || task.status() == AsyncTaskStatus.DEAD
                    || task.status() == AsyncTaskStatus.CANCELLED) {
                throw new ApplicationException(ErrorCode.ASYNC_TASK_NOT_RETRYABLE, "终态任务不能取消");
            }
            throw new ApplicationException(ErrorCode.COMMON_CONFLICT, "异步任务状态已变化");
        }
    }

    /** 按任务类型校验关联聚合的状态与版本仍适用于原任务。 */
    private void ensureRetryable(AsyncTask task) {
        if (task.status() != AsyncTaskStatus.DEAD || !matchesAggregate(task)) {
            throw new ApplicationException(ErrorCode.ASYNC_TASK_NOT_RETRYABLE,
                    "异步任务或关联业务对象不允许人工重试");
        }
    }

    /** 根据已确认规则判断任务关联的业务对象是否仍处于相同版本和目标状态。 */
    private boolean matchesAggregate(AsyncTask task) {
        return switch (task.taskType()) {
            case CASE_GENERATION -> task.aggregateType() == AggregateType.TICKET
                    && ticketRepository.findById(task.aggregateId())
                    .filter(value -> value.version() == task.aggregateVersion()
                            && value.status() == TicketStatus.RESOLVED).isPresent();
            case KNOWLEDGE_INDEX -> matchesIndexAggregate(task);
            case KNOWLEDGE_DELETE -> matchesArchivedAggregate(task);
        };
    }

    /** 校验知识索引任务关联的文档或案例仍处于发布中且版本一致。 */
    private boolean matchesIndexAggregate(AsyncTask task) {
        if (task.aggregateType() == AggregateType.MANAGED_DOCUMENT) {
            return documentRepository.findById(task.aggregateId())
                    .filter(value -> value.version() == task.aggregateVersion()
                            && value.status() == ManagedDocumentStatus.INDEXING).isPresent();
        }
        if (task.aggregateType() == AggregateType.RESOLVED_CASE) {
            return caseRepository.findById(task.aggregateId())
                    .filter(value -> value.version() == task.aggregateVersion()
                            && value.status() == ResolvedCaseStatus.PUBLISHING).isPresent();
        }
        return false;
    }

    /** 校验知识删除任务关联的文档或案例仍处于归档状态且版本一致。 */
    private boolean matchesArchivedAggregate(AsyncTask task) {
        if (task.aggregateType() == AggregateType.MANAGED_DOCUMENT) {
            return documentRepository.findById(task.aggregateId())
                    .filter(value -> value.version() == task.aggregateVersion()
                            && value.status() == ManagedDocumentStatus.ARCHIVED).isPresent();
        }
        if (task.aggregateType() == AggregateType.RESOLVED_CASE) {
            return caseRepository.findById(task.aggregateId())
                    .filter(value -> value.version() == task.aggregateVersion()
                            && value.status() == ResolvedCaseStatus.ARCHIVED).isPresent();
        }
        return false;
    }

    /** 查询任务，不存在时返回稳定公开错误。 */
    private AsyncTask requireTask(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("任务 ID 必须为正整数");
        }
        return taskRepository.findById(taskId).orElseThrow(() ->
                new ApplicationException(ErrorCode.ASYNC_TASK_NOT_FOUND, "异步任务不存在"));
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
}
