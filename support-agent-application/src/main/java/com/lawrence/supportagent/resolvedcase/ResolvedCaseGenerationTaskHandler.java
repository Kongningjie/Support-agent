package com.lawrence.supportagent.resolvedcase;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskCancelledException;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskHandler;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.idempotency.RequestFingerprint;
import com.lawrence.supportagent.knowledge.ExactTerm;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ChatModelPort.ResolvedCaseDraft;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketStatus;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.HashSet;
import java.util.Set;

/** 根据已解决工单事实异步生成唯一案例草稿。 */
public class ResolvedCaseGenerationTaskHandler implements AsyncTaskHandler {
    private static final String SYSTEM_OPERATOR = "case-generator";
    private final TicketRepository tickets;
    private final ResolvedCaseRepository cases;
    private final ChatModelPort model;
    private final ExactTermExtractor terms;
    private final TimeProvider time;

    /** 注入工单、案例、Chat 模型、精确词校验和时间端口。 */
    public ResolvedCaseGenerationTaskHandler(TicketRepository tickets,
                                             ResolvedCaseRepository cases,
                                             ChatModelPort model,
                                             ExactTermExtractor terms,
                                             TimeProvider time) {
        this.tickets = tickets;
        this.cases = cases;
        this.model = model;
        this.terms = terms;
        this.time = time;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskType taskType() {
        return AsyncTaskType.CASE_GENERATION;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
        AsyncTask task = context.task();
        Ticket ticket = requireCurrentTicket(task);
        if (cases.findBySourceTicketId(ticket.id()).isPresent()) {
            throw new AsyncTaskCancelledException("来源工单已经生成案例", AsyncTaskBusinessMutation.NONE);
        }
        try {
            ResolvedCaseDraft generated = model.generateResolvedCaseDraft(facts(ticket));
            String title = required(generated == null ? null : generated.title(), "案例标题", 160);
            String problem = required(generated == null ? null : generated.problem(), "问题描述", 4000);
            verifyNoNewExactTerms(ticket, title, problem);
            ResolvedCase draft = ResolvedCase.draft(ticket.id(), title, problem,
                    ticket.rootCause(), ticket.solution(),
                    RequestFingerprint.sha256(title, problem, ticket.rootCause(), ticket.solution()),
                    SYSTEM_OPERATOR, time.now());
            return () -> saveIfCurrent(task, draft);
        } catch (ModelInvocationException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(), exception.getMessage(),
                    exception.retryable());
        } catch (IllegalArgumentException exception) {
            throw new AsyncTaskExecutionException("CASE_GENERATION_SCHEMA_INVALID",
                    "案例结构化结果不符合事实或字段约束", true);
        }
    }

    /** 校验任务仍绑定相同版本的已解决工单。 */
    private Ticket requireCurrentTicket(AsyncTask task) {
        if (task.aggregateType() != AggregateType.TICKET) {
            throw new AsyncTaskExecutionException("CASE_GENERATION_AGGREGATE_INVALID",
                    "案例生成任务关联对象错误", false);
        }
        return tickets.findById(task.aggregateId()).filter(value ->
                        value.status() == TicketStatus.RESOLVED
                                && value.version() == task.aggregateVersion())
                .orElseThrow(() -> new AsyncTaskCancelledException(
                        "来源工单已不存在或版本状态已变化", AsyncTaskBusinessMutation.NONE));
    }

    /** 在任务成功短事务中再次检查工单和唯一案例后保存草稿。 */
    private void saveIfCurrent(AsyncTask task, ResolvedCase draft) {
        requireCurrentTicket(task);
        if (cases.findBySourceTicketId(task.aggregateId()).isEmpty()) cases.save(draft);
    }

    /** 只向模型提供已经持久化的工单事实。 */
    private String facts(Ticket ticket) {
        return "工单标题：" + ticket.title() + "\n问题描述：" + ticket.problemDescription()
                + "\n已尝试操作：" + (ticket.attemptedActions() == null ? "无" : ticket.attemptedActions());
    }

    /** 拒绝模型在标题或问题中新增版本、命令、路径等精确事实。 */
    private void verifyNoNewExactTerms(Ticket ticket, String title, String problem) {
        Set<String> allowed = normalizedTerms(facts(ticket));
        if (!allowed.containsAll(normalizedTerms(title + "\n" + problem))) {
            throw new IllegalArgumentException("模型生成了来源工单不存在的精确事实");
        }
    }

    /** 提取用于集合包含判断的类型和值组合。 */
    private Set<String> normalizedTerms(String text) {
        Set<String> values = new HashSet<>();
        for (ExactTerm term : terms.extract(text)) {
            values.add(term.type().name() + ":" + term.normalizedValue());
        }
        return values;
    }

    /** 校验模型结构化字段长度。 */
    private String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + "过长");
        return normalized;
    }
}
