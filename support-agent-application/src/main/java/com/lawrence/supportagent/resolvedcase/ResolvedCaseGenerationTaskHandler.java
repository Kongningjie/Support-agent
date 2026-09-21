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
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputAssessment;
import com.lawrence.supportagent.security.ModelOutputSecurityService;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.TicketStatus;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 根据已解决工单事实异步生成唯一案例草稿。 */
public class ResolvedCaseGenerationTaskHandler implements AsyncTaskHandler {
    private static final String SYSTEM_OPERATOR = "case-generator";
    private final TicketRepository tickets;
    private final ResolvedCaseRepository cases;
    private final ChatModelPort model;
    private final ExactTermExtractor terms;
    private final TimeProvider time;
    private final ModelOutputSecurityService outputSecurity;

    /** 注入工单、案例、Chat 模型、精确词校验和时间端口。 */
    public ResolvedCaseGenerationTaskHandler(TicketRepository tickets,
                                             ResolvedCaseRepository cases,
                                             ChatModelPort model,
                                             ExactTermExtractor terms,
                                             TimeProvider time) {
        this(tickets, cases, model, terms, time,
                ModelOutputSecurityService.standard(UUID::randomUUID));
    }

    /** 注入阶段 16 统一输出安全网关和全部既有依赖。 */
    public ResolvedCaseGenerationTaskHandler(TicketRepository tickets,
                                             ResolvedCaseRepository cases,
                                             ChatModelPort model,
                                             ExactTermExtractor terms,
                                             TimeProvider time,
                                             ModelOutputSecurityService outputSecurity) {
        this.tickets = tickets;
        this.cases = cases;
        this.model = model;
        this.terms = terms;
        this.time = time;
        this.outputSecurity = Objects.requireNonNull(outputSecurity, "模型输出安全服务不能为空");
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
            ResolvedCaseDraft generated = safeDraft(facts(ticket));
            String title = required(generated == null ? null : generated.title(), "案例标题", 160);
            String problem = required(generated == null ? null : generated.problem(), "问题描述", 4000);
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

    /** 对案例草稿执行完整输出安全检查，可修复失败最多完整重生成一次。 */
    private ResolvedCaseDraft safeDraft(String ticketFacts) {
        int regenerations = 0;
        List<String> feedback = List.of();
        while (true) {
            ModelInvocationSecurity invocation = outputSecurity.newInvocation(feedback);
            ResolvedCaseDraft generated;
            try {
                generated = model.generateResolvedCaseDraft(ticketFacts, invocation);
            } catch (ModelInvocationException exception) {
                if (exception.retryable() && outputSecurity.canRegenerate(regenerations)) {
                    regenerations++;
                    continue;
                }
                throw exception;
            }
            ModelOutputAssessment assessment = outputSecurity.assess(
                    ModelOutputType.RESOLVED_CASE_DRAFT, draftText(generated), invocation,
                    null, List.of());
            boolean exactTermsValid = generated != null && hasNoNewExactTerms(
                    ticketFacts, generated.title(), generated.problem());
            if (assessment.action() == ModelOutputAction.PASS && exactTermsValid) {
                return generated;
            }
            boolean correctable = assessment.action() == ModelOutputAction.REGENERATE
                    || assessment.action() == ModelOutputAction.PASS && !exactTermsValid;
            if (correctable && outputSecurity.canRegenerate(regenerations)) {
                regenerations++;
                feedback = assessment.action() == ModelOutputAction.PASS
                        ? List.of("EXACT_VALUE_NOT_SUPPORTED") : assessment.feedbackRules();
                continue;
            }
            throw new AsyncTaskExecutionException("CASE_GENERATION_OUTPUT_REJECTED",
                    "案例草稿未通过安全校验", false);
        }
    }

    /** 将结构化案例合并为仅用于内存安全检查的完整文本。 */
    private String draftText(ResolvedCaseDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()
                || draft.title().length() > 160
                || draft.problem() == null || draft.problem().isBlank()
                || draft.problem().length() > 4000) {
            return null;
        }
        return String.join("\n", String.valueOf(draft.title()), String.valueOf(draft.problem()));
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

    /** 判断模型是否没有在标题或问题中新增版本、命令、路径等精确事实。 */
    private boolean hasNoNewExactTerms(String ticketFacts, String title, String problem) {
        if (title == null || problem == null) {
            return false;
        }
        Set<String> allowed = normalizedTerms(ticketFacts);
        return allowed.containsAll(normalizedTerms(title + "\n" + problem));
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
