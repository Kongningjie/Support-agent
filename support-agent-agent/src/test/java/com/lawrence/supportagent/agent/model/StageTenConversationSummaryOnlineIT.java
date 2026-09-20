package com.lawrence.supportagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.agent.model.ConversationSummaryEvaluationDataset.Case;
import com.lawrence.supportagent.agent.model.ConversationSummaryEvaluationDataset.ExpectedEntity;
import com.lawrence.supportagent.chat.ChatIntent;
import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import com.lawrence.supportagent.chat.ConversationSummary;
import com.lawrence.supportagent.chat.ConversationSummaryKeyEntity;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.retrieval.RetrievalStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 qwen3.7-flash 验证阶段 10 固定中文摘要质量并输出不可变 JSON 报告。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class StageTenConversationSummaryOnlineIT {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();
    private final ExactTermExtractor exactTerms = new ExactTermExtractor();

    /** 运行 36 条在线语义用例并合并 4 条确定性故障用例的阶段门禁结果。 */
    @Test
    void shouldPassFrozenConversationSummaryQualityGate() throws Exception {
        var dataset = new ConversationSummaryEvaluationDataset(mapper).load();
        List<CaseResult> results = new ArrayList<>();
        for (Case testCase : dataset.cases()) {
            if (testCase.online()) {
                results.add(runOnline(testCase));
            } else {
                results.add(new CaseResult(testCase.caseId(), testCase.category(), true,
                        false, true, "SEPARATE_AUTOMATED_GATE", List.of(), 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0));
            }
        }
        long onlineTotal = results.stream().filter(CaseResult::online).count();
        long onlinePassed = results.stream().filter(result -> result.online() && result.passed()).count();
        double passRate = onlineTotal == 0 ? 0 : (double) onlinePassed / onlineTotal;
        double medianReduction = median(results.stream()
                .filter(result -> result.category().equals("LONG"))
                .map(CaseResult::tokenReduction).sorted().toList());
        SummaryReport report = new SummaryReport("conversation-summary-report-v1", UUID.randomUUID(),
                gitCommit(), dataset.version(), dataset.sha256(), modelName(), Instant.now(),
                new ParameterSnapshot(24_000, 1_200, 1_024, 6, 12, 6_000, 20, 1_200),
                results.size(), onlineTotal, onlinePassed, passRate,
                results.stream().filter(CaseResult::online).filter(CaseResult::schemaValid).count(),
                results.stream().mapToLong(CaseResult::expectedEntities).sum(),
                results.stream().mapToLong(CaseResult::matchedEntities).sum(),
                results.stream().mapToLong(CaseResult::unsourcedEntities).sum(),
                medianReduction, results);
        writeReport(report);

        assertThat(passRate).isGreaterThanOrEqualTo(0.95D);
        assertThat(results).filteredOn(result -> result.category().equals("DECISION")
                        || result.category().equals("ENTITY")
                        || result.category().equals("CORRECTION"))
                .allMatch(CaseResult::passed);
        assertThat(results).filteredOn(CaseResult::online).allMatch(CaseResult::schemaValid);
        assertThat(results).allMatch(result -> result.unsourcedEntities() == 0);
        assertThat(medianReduction).isGreaterThanOrEqualTo(0.30D);
    }

    /** 运行单条真实摘要用例并检查语义、过期事实和关键实体来源。 */
    private CaseResult runOnline(Case testCase) {
        List<DashScopeConversationSummaryAdapter.SummaryUsage> usages = new ArrayList<>();
        DashScopeConversationSummaryAdapter adapter = new DashScopeConversationSummaryAdapter(
                System.getenv("DASHSCOPE_API_KEY"), modelName(), baseUrl(), mapper,
                Duration.ofSeconds(30), 1_200, null, usages::add);
        List<CompletedTurn> turns = turns(testCase);
        int before = estimator.estimate(turns.stream().map(turn -> "用户：" + turn.userMessage()
                + "\n助手：" + turn.answer()).toList());
        try {
            ConversationSummary summary = summarize(testCase, adapter, turns);
            List<String> failures = evaluate(testCase, summary, turns);
            int after = estimator.estimate(summary.toModelContext());
            double reduction = before == 0 ? 0 : Math.max(0D, (double) (before - after) / before);
            long expectedEntities = testCase.expectedEntity() == null ? 0 : 1;
            long matchedEntities = expectedEntities == 1
                    && contains(summary, testCase.expectedEntity()) ? 1 : 0;
            long unsourcedEntities = countUnsourcedEntities(summary, turns);
            long matchedRequired = testCase.requiredPhrases().stream().filter(value ->
                    summary.toModelContext().toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT))).count();
            return new CaseResult(testCase.caseId(), testCase.category(), failures.isEmpty(), true,
                    true, "ONLINE_MODEL", failures, before, after, reduction,
                    usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::inputTokens).sum(),
                    usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::outputTokens).sum(),
                    usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::durationMs).sum(),
                    usages.size(), expectedEntities, matchedEntities, unsourcedEntities,
                    testCase.requiredPhrases().size(), matchedRequired);
        } catch (ModelInvocationException exception) {
            return failed(testCase, before, usages, exception.errorCode());
        } catch (RuntimeException exception) {
            return failed(testCase, before, usages, "UNEXPECTED_EVALUATION_FAILURE");
        }
    }

    /** 普通用例生成一次摘要，LONG 用例执行两次滚动摘要以覆盖真实更新路径。 */
    private ConversationSummary summarize(Case testCase,
                                          DashScopeConversationSummaryAdapter adapter,
                                          List<CompletedTurn> turns) {
        if (!"LONG".equals(testCase.category())) {
            return summarizeWithRetry(adapter, null, turns, 1,
                    turns.getLast().conversationVersion());
        }
        int middle = turns.size() / 2;
        List<CompletedTurn> first = turns.subList(0, middle);
        List<CompletedTurn> second = turns.subList(middle, turns.size());
        ConversationSummary previous = summarizeWithRetry(adapter, null, first, 1,
                first.getLast().conversationVersion());
        return summarizeWithRetry(adapter, previous, second, 2,
                second.getLast().conversationVersion());
    }

    /** 按生产规则仅对可重试模型故障完整重试一次。 */
    private ConversationSummary summarizeWithRetry(DashScopeConversationSummaryAdapter adapter,
                                                   ConversationSummary previous,
                                                   List<CompletedTurn> turns,
                                                   long summaryVersion, long coveredVersion) {
        try {
            return adapter.summarize(previous, turns, summaryVersion, coveredVersion);
        } catch (ModelInvocationException exception) {
            if (!exception.retryable()) {
                throw exception;
            }
            return adapter.summarize(previous, turns, summaryVersion, coveredVersion);
        }
    }

    /** 构造不含模型正文或供应商响应的失败结果。 */
    private CaseResult failed(Case testCase, int before,
                              List<DashScopeConversationSummaryAdapter.SummaryUsage> usages,
                              String errorCode) {
        return new CaseResult(testCase.caseId(), testCase.category(), false, true, false,
                "ONLINE_MODEL", List.of(errorCode), before, 0, 0,
                usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::inputTokens).sum(),
                usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::outputTokens).sum(),
                usages.stream().mapToLong(DashScopeConversationSummaryAdapter.SummaryUsage::durationMs).sum(),
                usages.size(), testCase.expectedEntity() == null ? 0 : 1, 0, 0,
                testCase.requiredPhrases().size(), 0);
    }

    /** 检查摘要必须保留、必须淘汰以及必须精确保留的字段。 */
    private List<String> evaluate(Case testCase, ConversationSummary summary,
                                  List<CompletedTurn> turns) {
        List<String> failures = new ArrayList<>();
        String rendered = summary.toModelContext().toLowerCase(Locale.ROOT);
        testCase.requiredPhrases().stream().filter(value -> !rendered.contains(
                value.toLowerCase(Locale.ROOT))).forEach(value -> failures.add("MISSING:" + value));
        testCase.forbiddenPhrases().stream().filter(value -> rendered.contains(
                value.toLowerCase(Locale.ROOT))).forEach(value -> failures.add("STALE:" + value));
        if (testCase.expectedEntity() != null && !contains(summary, testCase.expectedEntity())) {
            failures.add("ENTITY_MISSING:" + testCase.expectedEntity().normalizedValue());
        }
        if (countUnsourcedEntities(summary, turns) > 0) {
            failures.add("ENTITY_WITHOUT_SOURCE");
        }
        return List.copyOf(failures);
    }

    /** 判断摘要是否包含用例指定类型、规范值和来源版本的关键实体。 */
    private boolean contains(ConversationSummary summary, ExpectedEntity expected) {
        return summary.keyEntities().stream().anyMatch(entity ->
                entity.type().name().equals(expected.type())
                        && entity.normalizedValue().equals(expected.normalizedValue())
                        && entity.sourceTurnVersions().contains(expected.sourceTurnVersion()));
    }

    /** 确认模型返回的所有实体都能在原始轮次确定性提取结果中找到。 */
    private long countUnsourcedEntities(ConversationSummary summary, List<CompletedTurn> turns) {
        Set<String> allowed = new HashSet<>();
        for (CompletedTurn turn : turns) {
            exactTerms.extract(turn.userMessage() + "\n" + turn.answer()).forEach(term ->
                    allowed.add(term.type().name() + '\u0000' + term.normalizedValue()
                            + '\u0000' + turn.conversationVersion()));
        }
        return summary.keyEntities().stream().filter(entity ->
                entity.sourceTurnVersions().stream().anyMatch(source ->
                        !allowed.contains(entity.type().name() + '\u0000' + entity.normalizedValue()
                                + '\u0000' + source))).count();
    }

    /** 将固定数据轮次转换为生产摘要端口使用的成功轮次。 */
    private List<CompletedTurn> turns(Case testCase) {
        return testCase.turns().stream().map(turn -> new CompletedTurn(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), turn.user(), turn.user(),
                ChatIntent.SUPPORT_QUERY, turn.assistant(), RetrievalStatus.GROUNDED,
                List.of(), null, null, "GROUNDED", Instant.EPOCH, turn.version())).toList();
    }

    /** 返回真实摘要模型名称，允许在线环境显式覆盖。 */
    private String modelName() {
        return System.getenv().getOrDefault("SUPPORT_AGENT_SUMMARY_MODEL", "qwen3.7-flash");
    }

    /** 返回真实 DashScope 地址，允许工作空间专属地址覆盖公共地址。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }

    /** 计算有序 Token 降幅集合的中位数。 */
    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0D;
        }
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2D
                : values.get(middle);
    }

    /** 将动态结果写入 target，文件不进入 Git。 */
    private void writeReport(SummaryReport report) throws Exception {
        Path directory = Path.of("target", "stage-10-summary");
        Files.createDirectories(directory);
        Path target = directory.resolve("summary-" + report.runId() + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
    }

    /** 返回当前工作树 HEAD；Git 不可用时返回 unknown 但不影响质量判定。 */
    private String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && output.matches("[0-9a-fA-F]{40}") ? output : "unknown";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unknown";
        } catch (IOException exception) {
            return "unknown";
        }
    }

    /** 单条用例报告，不包含完整会话或摘要正文。 */
    private record CaseResult(String caseId, String category, boolean passed, boolean online,
                              boolean schemaValid, String verification, List<String> failures,
                              int estimatedTokensBefore, int estimatedTokensAfter,
                              double tokenReduction, long inputTokens, long outputTokens,
                              long durationMs, long modelAttempts, long expectedEntities,
                              long matchedEntities, long unsourcedEntities,
                              long requiredPhrases, long matchedRequiredPhrases) {
        /** 复制失败码，防止报告内容在写出前被修改。 */
        private CaseResult {
            failures = List.copyOf(failures);
        }
    }

    /** 阶段 10 冻结参数快照，防止报告与实际门禁口径脱节。 */
    private record ParameterSnapshot(int inputBudgetTokens, int outputReserveTokens,
                                     int safetyMarginTokens, int recentFullTurns,
                                     int softTriggerTurns, int softTriggerMemoryTokens,
                                     int hardTriggerTurns, int summaryMaxOutputTokens) { }

    /** 阶段 10 动态报告根对象，不保存任何测试正文或模型输出。 */
    private record SummaryReport(String schemaVersion, UUID runId, String gitCommit,
                                 String datasetVersion, String datasetSha256, String modelName,
                                 Instant startedAt, ParameterSnapshot parameters,
                                 long totalCases, long onlineCases,
                                 long onlinePassed, double onlinePassRate,
                                 long schemaPassed, long expectedEntities,
                                 long matchedEntities, long unsourcedEntities,
                                 double medianTokenReduction, List<CaseResult> cases) {
        /** 复制用例结果，确保写出的报告不可变。 */
        private SummaryReport {
            cases = List.copyOf(cases);
        }
    }
}
