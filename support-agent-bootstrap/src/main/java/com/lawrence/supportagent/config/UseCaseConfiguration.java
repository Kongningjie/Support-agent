package com.lawrence.supportagent.config;

import com.lawrence.supportagent.asynctask.AsyncTaskCreator;
import com.lawrence.supportagent.asynctask.AsyncTaskHandler;
import com.lawrence.supportagent.asynctask.AsyncTaskRunner;
import com.lawrence.supportagent.asynctask.AsyncTaskUseCase;
import com.lawrence.supportagent.asynctask.port.AsyncTaskRepository;
import com.lawrence.supportagent.asynctask.port.AsyncTaskCompletionPort;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseQueryUseCase;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseGenerationTaskHandler;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.ticket.TicketCommandUseCase;
import com.lawrence.supportagent.ticket.TicketQueryUseCase;
import com.lawrence.supportagent.ticket.SuggestedTicketUseCase;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.model.ChatModelPort;
import com.lawrence.supportagent.ticket.port.TicketRepository;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在启动模块装配通用任务与工单应用用例，保持应用模块不依赖 Spring。 */
@Configuration
public class UseCaseConfiguration {
    /** 创建工单只读查询用例。 */
    @Bean
    public TicketQueryUseCase ticketQueryUseCase(TicketRepository repository) {
        return new TicketQueryUseCase(repository);
    }

    /** 创建具有外部幂等保护的工单命令用例。 */
    @Bean
    public TicketCommandUseCase ticketCommandUseCase(TicketRepository repository,
                                                      TicketQueryUseCase queryUseCase,
                                                      IdempotentExecutor executor,
                                                      OperatorProvider operatorProvider,
                                                      TimeProvider timeProvider,
                                                      AsyncTaskCreator taskCreator) {
        return new TicketCommandUseCase(repository, queryUseCase, executor,
                operatorProvider, timeProvider, taskCreator);
    }

    /** 创建显式消费会话建议的工单草稿用例。 */
    @Bean
    public SuggestedTicketUseCase suggestedTicketUseCase(ConversationStorePort conversations,
                                                          ChatModelPort model,
                                                          TicketCommandUseCase commands,
                                                          TicketQueryUseCase queries,
                                                          TimeProvider timeProvider) {
        return new SuggestedTicketUseCase(conversations, model, commands, queries, timeProvider);
    }

    /** 创建供后续业务事务内投递任务的统一入口。 */
    @Bean
    public AsyncTaskCreator asyncTaskCreator(AsyncTaskRepository repository,
                                              TimeProvider timeProvider) {
        return new AsyncTaskCreator(repository, timeProvider);
    }

    /** 创建任务执行器并按任务类型收集当前阶段已注册的 Handler。 */
    @Bean
    public AsyncTaskRunner asyncTaskRunner(AsyncTaskRepository repository,
                                            AsyncTaskCompletionPort completionPort,
                                            TimeProvider timeProvider,
                                            ObjectProvider<AsyncTaskHandler> handlers) {
        List<AsyncTaskHandler> availableHandlers = handlers.orderedStream().toList();
        return new AsyncTaskRunner(repository, completionPort, timeProvider, availableHandlers);
    }

    /** 创建任务查询、取消及人工重试用例。 */
    @Bean
    public AsyncTaskUseCase asyncTaskUseCase(AsyncTaskRepository taskRepository,
                                              TicketRepository ticketRepository,
                                              ManagedDocumentRepository documentRepository,
                                              ResolvedCaseRepository caseRepository,
                                              IdempotentExecutor executor,
                                              OperatorProvider operatorProvider,
                                              TimeProvider timeProvider) {
        return new AsyncTaskUseCase(taskRepository, ticketRepository, documentRepository,
                caseRepository, executor, operatorProvider, timeProvider);
    }

    /** 创建已解决案例查询用例。 */
    @Bean
    public ResolvedCaseQueryUseCase resolvedCaseQueryUseCase(ResolvedCaseRepository cases,
                                                              TicketRepository tickets) {
        return new ResolvedCaseQueryUseCase(cases, tickets);
    }

    /** 注册已解决工单的异步案例草稿生成处理器。 */
    @Bean
    public ResolvedCaseGenerationTaskHandler resolvedCaseGenerationTaskHandler(
            TicketRepository tickets, ResolvedCaseRepository cases,
            ChatModelPort model, ExactTermExtractor terms, TimeProvider time) {
        return new ResolvedCaseGenerationTaskHandler(tickets, cases, model, terms, time);
    }
}
