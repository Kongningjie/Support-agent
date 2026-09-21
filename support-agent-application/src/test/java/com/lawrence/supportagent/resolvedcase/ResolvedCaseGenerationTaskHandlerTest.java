package com.lawrence.supportagent.resolvedcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.model.ModelInvocationSecurity;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.ticket.Ticket;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证案例生成只整理问题且原样复制人工确认结论。 */
class ResolvedCaseGenerationTaskHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-08T01:00:00Z");

    /** 验证成功生成时 cause 和 solution 不取模型值且只保存一个草稿。 */
    @Test
    void shouldCopyConfirmedCauseAndSolutionExactly() {
        TicketRepository tickets = mock(TicketRepository.class);
        ResolvedCaseRepository cases = mock(ResolvedCaseRepository.class);
        ChatModelPort model = mock(ChatModelPort.class);
        Ticket ticket = resolvedTicket();
        when(tickets.findById(1)).thenReturn(Optional.of(ticket));
        when(cases.findBySourceTicketId(1)).thenReturn(Optional.empty());
        when(model.generateResolvedCaseDraft(any(), any(ModelInvocationSecurity.class))).thenReturn(
                new ChatModelPort.ResolvedCaseDraft("MySQL 连接失败", "应用无法连接 MySQL"));
        AsyncTask task = AsyncTask.pending(AsyncTaskType.CASE_GENERATION, AggregateType.TICKET,
                1, ticket.version(), "case-generation:1:2", "dev-operator", NOW);
        ResolvedCaseGenerationTaskHandler handler = new ResolvedCaseGenerationTaskHandler(
                tickets, cases, model, new ExactTermExtractor(), () -> NOW);

        handler.execute(new AsyncTaskExecutionContext(task, () -> true)).apply();

        ArgumentCaptor<ResolvedCase> captor = ArgumentCaptor.forClass(ResolvedCase.class);
        verify(cases).save(captor.capture());
        assertEquals("人工确认根因", captor.getValue().cause());
        assertEquals("人工验证方案", captor.getValue().solution());
        assertEquals(ResolvedCaseStatus.DRAFT, captor.getValue().status());
    }

    /** 验证模型连续新增来源工单中不存在的 URL 时完整重生成一次后拒绝。 */
    @Test
    void shouldRejectInventedExactFact() {
        TicketRepository tickets = mock(TicketRepository.class);
        ResolvedCaseRepository cases = mock(ResolvedCaseRepository.class);
        ChatModelPort model = mock(ChatModelPort.class);
        Ticket ticket = resolvedTicket();
        when(tickets.findById(1)).thenReturn(Optional.of(ticket));
        when(cases.findBySourceTicketId(1)).thenReturn(Optional.empty());
        when(model.generateResolvedCaseDraft(any(), any(ModelInvocationSecurity.class))).thenReturn(new ChatModelPort.ResolvedCaseDraft(
                "MySQL 连接失败", "访问 https://invented.example.com 后应用恢复"));
        AsyncTask task = AsyncTask.pending(AsyncTaskType.CASE_GENERATION, AggregateType.TICKET,
                1, ticket.version(), "case-generation:1:2", "dev-operator", NOW);
        ResolvedCaseGenerationTaskHandler handler = new ResolvedCaseGenerationTaskHandler(
                tickets, cases, model, new ExactTermExtractor(), () -> NOW);

        AsyncTaskExecutionException failure = assertThrows(AsyncTaskExecutionException.class,
                () -> handler.execute(new AsyncTaskExecutionContext(task, () -> true)));

        assertEquals("CASE_GENERATION_OUTPUT_REJECTED", failure.errorCode());
        assertTrue(!failure.retryable());
        verify(model, times(2)).generateResolvedCaseDraft(any(), any(ModelInvocationSecurity.class));
        verify(cases, never()).save(any());
    }

    /** 验证案例草稿泄漏随机标记时立即拒绝且不落库、不重生成。 */
    @Test
    void shouldRejectCanaryLeakBeforeCasePersistence() {
        TicketRepository tickets = mock(TicketRepository.class);
        ResolvedCaseRepository cases = mock(ResolvedCaseRepository.class);
        ChatModelPort model = mock(ChatModelPort.class);
        Ticket ticket = resolvedTicket();
        when(tickets.findById(1)).thenReturn(Optional.of(ticket));
        when(cases.findBySourceTicketId(1)).thenReturn(Optional.empty());
        when(model.generateResolvedCaseDraft(any(), any(ModelInvocationSecurity.class)))
                .thenAnswer(invocation -> new ChatModelPort.ResolvedCaseDraft(
                        invocation.<ModelInvocationSecurity>getArgument(1).canary(), "应用无法连接 MySQL"));
        AsyncTask task = AsyncTask.pending(AsyncTaskType.CASE_GENERATION, AggregateType.TICKET,
                1, ticket.version(), "case-generation:1:2", "dev-operator", NOW);
        ResolvedCaseGenerationTaskHandler handler = new ResolvedCaseGenerationTaskHandler(
                tickets, cases, model, new ExactTermExtractor(), () -> NOW);

        AsyncTaskExecutionException failure = assertThrows(AsyncTaskExecutionException.class,
                () -> handler.execute(new AsyncTaskExecutionContext(task, () -> true)));

        assertEquals("CASE_GENERATION_OUTPUT_REJECTED", failure.errorCode());
        verify(model, times(1)).generateResolvedCaseDraft(any(), any(ModelInvocationSecurity.class));
        verify(cases, never()).save(any());
    }

    /** 创建具备固定内部 ID 和已解决版本的工单。 */
    private Ticket resolvedTicket() {
        Ticket draft = Ticket.draft(null, null,
                java.util.UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "MySQL 连接失败", "应用无法连接 MySQL",
                null, "dev-operator", NOW).assignNumber(1, "T000000000001");
        return draft.submit("dev-operator", NOW).resolve(
                "人工确认根因", "人工验证方案", "dev-operator", NOW);
    }
}
