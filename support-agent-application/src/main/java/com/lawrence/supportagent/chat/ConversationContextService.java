package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.chat.port.ConversationStorePort.MemorySnapshot;
import com.lawrence.supportagent.knowledge.ExactTerm;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.model.ConversationSummaryPort;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/** 编排单会话 Token 预算、滚动摘要、实体校验和 Redis CAS 提交。 */
public class ConversationContextService {
    private final ConversationStorePort store;
    private final ConversationSummaryPort summaryModel;
    private final ExactTermExtractor exactTerms;
    private final DocumentContentPolicy contentPolicy;
    private final ConservativeTokenEstimator tokens;
    private final ConversationMemorySettings settings;
    private final Executor summaryExecutor;
    private final TimeProvider time;

    /** 注入会话存储、独立摘要模型、确定性校验器、预算、执行器和统一时钟。 */
    public ConversationContextService(ConversationStorePort store,
                                      ConversationSummaryPort summaryModel,
                                      ExactTermExtractor exactTerms,
                                      DocumentContentPolicy contentPolicy,
                                      ConservativeTokenEstimator tokens,
                                      ConversationMemorySettings settings,
                                      Executor summaryExecutor, TimeProvider time) {
        this.store = store;
        this.summaryModel = summaryModel;
        this.exactTerms = exactTerms;
        this.contentPolicy = contentPolicy;
        this.tokens = tokens;
        this.settings = settings;
        this.summaryExecutor = summaryExecutor;
        this.time = time;
    }

    /**
     * 为一次模型调用准备预算内上下文；达到硬阈值时先同步生成并提交摘要。
     *
     * @param conversationId 当前会话 ID
     * @param fixedSections 当前问题、证据或工单边界等不可由历史挤占的内容
     * @return 可直接传递给模型端口的上下文窗口
     */
    public ConversationContext prepare(UUID ownerUserId, UUID conversationId, List<String> fixedSections) {
        MemorySnapshot snapshot = store.memorySnapshot(ownerUserId, conversationId);
        if (requiresHardSummary(snapshot, fixedSections)) {
            long previousSummaryVersion = snapshot.summaryVersion();
            summarize(ownerUserId, snapshot, true);
            snapshot = store.memorySnapshot(ownerUserId, conversationId);
            if (snapshot.summaryVersion() <= previousSummaryVersion
                    && requiresHardSummary(snapshot, fixedSections)) {
                throw unavailable(null);
            }
        }
        return assemble(snapshot, fixedSections);
    }

    /** 在成功轮次提交后按软阈值异步生成摘要，失败时保留全部原始轮次。 */
    public void afterSuccessfulTurn(UUID ownerUserId, UUID conversationId) {
        try {
            MemorySnapshot snapshot = store.memorySnapshot(ownerUserId, conversationId);
            if (requiresSoftSummary(snapshot)) {
                summaryExecutor.execute(() -> summarize(ownerUserId, snapshot, false));
            }
        } catch (RuntimeException ignored) {
            // 软触发只做尽力调度；原始轮次保留并由下一次请求重新判断。
        }
    }

    /** 判断原始轮次或完整调用预算是否要求在当前模型调用前同步摘要。 */
    private boolean requiresHardSummary(MemorySnapshot snapshot, List<String> fixedSections) {
        return snapshot.turns().size() >= settings.hardTriggerTurns()
                || totalTokens(snapshot, fixedSections) > settings.availableInputTokens();
    }

    /** 判断成功轮次数量或会话记忆估算是否达到异步摘要软阈值。 */
    private boolean requiresSoftSummary(MemorySnapshot snapshot) {
        return compressible(snapshot)
                && (snapshot.turns().size() >= settings.softTriggerTurns()
                || memoryTokens(snapshot) >= settings.softTriggerMemoryTokens());
    }

    /** 执行一次结构化摘要、确定性实体校验和 Redis CAS 提交。 */
    private void summarize(UUID ownerUserId, MemorySnapshot snapshot, boolean required) {
        List<CompletedTurn> source = sourceTurns(snapshot.turns());
        if (source.isEmpty()) {
            if (required && totalTokens(snapshot, List.of()) > settings.availableInputTokens()) {
                throw unavailable(null);
            }
            return;
        }
        long covered = source.getLast().conversationVersion();
        try {
            ConversationSummary candidate = generateSummary(snapshot, source, covered);
            validateCandidate(snapshot, source, candidate, covered);
            store.commitSummary(ownerUserId, snapshot.conversationId(), snapshot.summaryVersion(),
                    candidate, settings.recentFullTurns(), time.now());
        } catch (RuntimeException exception) {
            if (required) {
                throw unavailable(exception);
            }
        }
    }

    /** 对临时模型故障最多重试一次，确定性结构或实体错误不盲目重试。 */
    private ConversationSummary generateSummary(MemorySnapshot snapshot,
                                                List<CompletedTurn> source, long covered) {
        try {
            return summaryModel.summarize(snapshot.summary(), source,
                    snapshot.summaryVersion() + 1, covered);
        } catch (ModelInvocationException exception) {
            if (!exception.retryable()) {
                throw exception;
            }
            return summaryModel.summarize(snapshot.summary(), source,
                    snapshot.summaryVersion() + 1, covered);
        }
    }

    /** 返回可被本次摘要覆盖的较早轮次，并始终保留冻结数量的最近完整轮次。 */
    private List<CompletedTurn> sourceTurns(List<CompletedTurn> turns) {
        int end = turns.size() - settings.recentFullTurns();
        return end <= 0 ? List.of() : List.copyOf(turns.subList(0, end));
    }

    /** 校验模型返回版本、覆盖边界以及所有关键实体均可回溯到来源轮次。 */
    private void validateCandidate(MemorySnapshot snapshot, List<CompletedTurn> source,
                                   ConversationSummary candidate, long covered) {
        if (candidate.summaryVersion() != snapshot.summaryVersion() + 1
                || candidate.coveredThroughVersion() != covered) {
            throw new IllegalArgumentException("摘要模型返回的版本或覆盖边界不合法");
        }
        contentPolicy.verifyNoSensitiveContent(candidate.toModelContext());
        Map<String, Set<Long>> allowed = allowedEntities(snapshot.summary(), source);
        for (ConversationSummaryKeyEntity entity : candidate.keyEntities()) {
            String key = entity.type().name() + '\u0000' + entity.normalizedValue();
            Set<Long> versions = allowed.get(key);
            if (versions == null || !versions.containsAll(entity.sourceTurnVersions())) {
                throw new IllegalArgumentException("摘要模型生成了无法回溯的关键实体");
            }
        }
    }

    /** 汇总已有摘要与本轮来源中的可用关键实体及其来源版本。 */
    private Map<String, Set<Long>> allowedEntities(ConversationSummary previous,
                                                    List<CompletedTurn> source) {
        Map<String, Set<Long>> allowed = new HashMap<>();
        if (previous != null) {
            for (ConversationSummaryKeyEntity entity : previous.keyEntities()) {
                allowed.computeIfAbsent(entity.type().name() + '\u0000' + entity.normalizedValue(),
                        ignored -> new HashSet<>()).addAll(entity.sourceTurnVersions());
            }
        }
        for (CompletedTurn turn : source) {
            for (ExactTerm term : exactTerms.extract(turn.userMessage() + "\n" + turn.answer())) {
                allowed.computeIfAbsent(term.type().name() + '\u0000' + term.normalizedValue(),
                        ignored -> new HashSet<>()).add(turn.conversationVersion());
            }
        }
        return allowed;
    }

    /** 在冻结预算内按“摘要优先、最近轮次从新到旧”的顺序组装上下文。 */
    private ConversationContext assemble(MemorySnapshot snapshot, List<String> fixedSections) {
        int fixedTokens = tokens.estimate(fixedSections);
        int remaining = settings.availableInputTokens() - fixedTokens;
        if (remaining < 0) {
            throw new ApplicationException(ErrorCode.COMMON_VALIDATION_FAILED,
                    "当前问题和证据超过模型输入预算");
        }
        List<String> result = new ArrayList<>();
        if (snapshot.summary() != null) {
            String summary = snapshot.summary().toModelContext();
            int summaryTokens = tokens.estimate(summary);
            if (summaryTokens > remaining) {
                throw new ApplicationException(ErrorCode.COMMON_VALIDATION_FAILED,
                        "会话摘要超过模型输入预算");
            }
            result.add(summary);
            remaining -= summaryTokens;
        }
        List<String> selectedNewestFirst = new ArrayList<>();
        List<CompletedTurn> turns = snapshot.turns();
        for (int index = turns.size() - 1;
             index >= 0 && selectedNewestFirst.size() < settings.recentFullTurns(); index--) {
            String value = render(turns.get(index));
            int turnTokens = tokens.estimate(value);
            if (turnTokens > remaining) {
                break;
            }
            selectedNewestFirst.add(value);
            remaining -= turnTokens;
        }
        for (int index = selectedNewestFirst.size() - 1; index >= 0; index--) {
            result.add(selectedNewestFirst.get(index));
        }
        return new ConversationContext(result, settings.availableInputTokens() - remaining,
                snapshot.summaryVersion(), snapshot.summary() == null
                ? 0 : snapshot.summary().coveredThroughVersion());
    }

    /** 估算完整会话记忆但不包含当前调用固定内容的 Token。 */
    private int memoryTokens(MemorySnapshot snapshot) {
        List<String> values = new ArrayList<>();
        if (snapshot.summary() != null) {
            values.add(snapshot.summary().toModelContext());
        }
        snapshot.turns().stream().map(this::render).forEach(values::add);
        return tokens.estimate(values);
    }

    /** 估算会话记忆、当前问题和当前证据的合计输入 Token。 */
    private int totalTokens(MemorySnapshot snapshot, List<String> fixedSections) {
        return memoryTokens(snapshot) + tokens.estimate(fixedSections);
    }

    /** 判断当前快照是否存在至少一个可压缩且不会侵入最近窗口的轮次。 */
    private boolean compressible(MemorySnapshot snapshot) {
        return snapshot.turns().size() > settings.recentFullTurns();
    }

    /** 将一个成功轮次渲染为与模型适配器约定一致的历史片段。 */
    private String render(CompletedTurn turn) {
        return "用户：" + turn.userMessage() + "\n助手：" + turn.answer();
    }

    /** 把同步摘要失败映射为不会泄露模型响应的稳定应用错误。 */
    private ApplicationException unavailable(RuntimeException cause) {
        String message = cause == null ? "会话上下文超过预算且无法继续压缩"
                : "会话摘要模型暂时不可用或结果未通过安全校验";
        return new ApplicationException(ErrorCode.CHAT_MODEL_UNAVAILABLE, message);
    }
}
