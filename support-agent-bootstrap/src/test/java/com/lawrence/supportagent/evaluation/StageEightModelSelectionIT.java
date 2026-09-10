package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lawrence.supportagent.agent.model.DashScopeChatModelAdapter;
import com.lawrence.supportagent.agent.model.GroundedPromptVariant;
import com.lawrence.supportagent.agent.model.ModelGenerationSettings;
import com.lawrence.supportagent.chat.AnswerValidator;
import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.model.ChatModelPort.ResolvedCaseDraft;
import com.lawrence.supportagent.model.ModelInvocationException;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.ModelOperation;
import com.lawrence.supportagent.observability.OptimizationTelemetryPort.Operation;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用同一冻结数据、Prompt 和参数执行阶段 8 三模型真实回答评测。 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class StageEightModelSelectionIT {
    private static final List<String> CANDIDATES = List.of("qwen3.7-flash-2026-07-15",
            "qwen3.8-flash", "qwen3.8-max-0902");
    private static final long WARNING_TOKENS = 80_000;
    private static final long HARD_LIMIT_TOKENS = 100_000;
    private static final String NO_KNOWLEDGE = "当前知识库中没有找到足够可靠的依据，因此我不能给出确定的处理步骤。你可以确认创建一个技术支持工单，由人工继续处理。";
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final AnswerQualityEvaluator quality = new AnswerQualityEvaluator();
    private final AnswerValidator answerValidator = new AnswerValidator(
            new ExactTermExtractor(), new DocumentContentPolicy());

    /** 比较三个候选，应用硬门禁，并只对胜出模型执行 Prompt 压缩 A/B。 */
    @Test
    void shouldSelectModelAndEvaluatePromptCompressionWithinBudget() throws Exception {
        Instant startedAt = Instant.now();
        AnswerEvaluationDatasetSnapshot dataset = new ClasspathAnswerEvaluationDatasetAdapter(mapper).load();
        List<CandidateRun> runs = new ArrayList<>();
        for (String candidate : CANDIDATES) {
            runs.add(evaluate(candidate, GroundedPromptVariant.ORIGINAL, dataset,
                    HARD_LIMIT_TOKENS));
        }
        List<AnswerModelCandidateResult> selectionInputs = selectionInputs(runs);
        AnswerModelSelection selection = new AnswerModelSelectionPolicy().select(selectionInputs);
        PromptComparison promptComparison = null;
        CandidateRun promptCandidate = null;
        if (selection.status() == AnswerModelSelection.Status.SELECTED) {
            CandidateRun original = runs.stream().filter(value ->
                    value.modelName().equals(selection.modelName())).findFirst().orElseThrow();
            long remainingBudget = Math.max(0, HARD_LIMIT_TOKENS - original.totalTokens());
            promptCandidate = evaluate(selection.modelName(), GroundedPromptVariant.COMPACT,
                    dataset, remainingBudget);
            promptComparison = comparePrompts(original, promptCandidate);
        }
        StageEightReport report = new StageEightReport("1.0", UUID.randomUUID(),
                new WorkingTreeGitCommitResolver(Path.of("..")).resolve(), startedAt, Instant.now(),
                dataset.version(), dataset.contentSha256(), CANDIDATES,
                ModelGenerationSettings.stageEightDefaults(), WARNING_TOKENS, HARD_LIMIT_TOKENS,
                runs, selection, promptCandidate, promptComparison, selectionRules());
        writeReport(report);
        assertNotEquals(AnswerModelSelection.Status.NO_QUALIFIED_CANDIDATE, selection.status(),
                "至少一个候选必须通过全部回答质量硬门禁，详情见 target/stage-8-optimization");
        assertTrue(runs.stream().allMatch(value -> value.totalTokens() <= HARD_LIMIT_TOKENS),
                "任何候选均不得超过独立 Token 硬上限");
    }

    /** 使用同一 Prompt 版本和生成参数运行一个候选模型的全部三十条用例。 */
    private CandidateRun evaluate(String modelName, GroundedPromptVariant promptVariant,
                                  AnswerEvaluationDatasetSnapshot dataset, long tokenBudget) {
        CapturingTelemetry telemetry = new CapturingTelemetry();
        DashScopeChatModelAdapter adapter = new DashScopeChatModelAdapter(apiKey(), modelName,
                baseUrl(), mapper, telemetry, ModelGenerationSettings.stageEightDefaults(), promptVariant);
        List<CaseRun> results = new ArrayList<>();
        int productionRegenerations = 0;
        int stabilityReruns = 0;
        boolean firstRoundAllPassed = true;
        for (AnswerEvaluationCase testCase : dataset.cases()) {
            if (telemetry.totalTokens() >= tokenBudget) {
                results.add(new CaseRun(testCase.caseId(), false, List.of("TOKEN_BUDGET_EXHAUSTED"),
                        List.of(), List.of(), 0, 0, 0, 0, false, false));
                firstRoundAllPassed = false;
                continue;
            }
            CaseRun first = attemptCase(adapter, telemetry, testCase, false);
            if (first.productionRegenerated()) productionRegenerations++;
            CaseRun result = first;
            if (!first.passed() && telemetry.totalTokens() < tokenBudget) {
                stabilityReruns++;
                firstRoundAllPassed = false;
                result = attemptCase(adapter, telemetry, testCase, true)
                        .withFirstEvaluationFailures(first.failures());
                if (result.productionRegenerated()) productionRegenerations++;
            }
            if (!first.passed() || first.productionRegenerated()) firstRoundAllPassed = false;
            results.add(result);
        }
        List<Long> complete = results.stream().filter(value -> value.completeMs() > 0)
                .map(CaseRun::completeMs).toList();
        List<Long> first = results.stream().filter(value -> value.firstTokenMs() > 0)
                .map(CaseRun::firstTokenMs).toList();
        long totalTokens = telemetry.totalTokens();
        return new CandidateRun(modelName, promptVariant, results.stream().allMatch(CaseRun::passed),
                firstRoundAllPassed, productionRegenerations, stabilityReruns,
                totalTokens >= WARNING_TOKENS,
                totalTokens, telemetry.inputTokens(), telemetry.outputTokens(),
                LatencySummary.from(first), LatencySummary.from(complete), List.copyOf(results));
    }

    /** 执行一条完整生产链路，并将供应商异常转为可评测的失败结果。 */
    private CaseRun attemptCase(DashScopeChatModelAdapter adapter,
                                CapturingTelemetry telemetry,
                                AnswerEvaluationCase testCase, boolean stabilityRerun) {
        long inputBefore = telemetry.inputTokens();
        long outputBefore = telemetry.outputTokens();
        long started = System.nanoTime();
        try {
            return runCase(adapter, telemetry, testCase, stabilityRerun);
        } catch (ModelInvocationException exception) {
            return new CaseRun(testCase.caseId(), false,
                    List.of("MODEL_INVOCATION_" + exception.errorCode()), List.of(), List.of(),
                    0, nanosToMillis(System.nanoTime() - started),
                    telemetry.inputTokens() - inputBefore,
                    telemetry.outputTokens() - outputBefore, false, stabilityRerun);
        }
    }

    /** 调用用例对应的模型能力并执行程序化硬门禁。 */
    private CaseRun runCase(DashScopeChatModelAdapter adapter, CapturingTelemetry telemetry,
                            AnswerEvaluationCase testCase, boolean stabilityRerun) {
        long inputBefore = telemetry.inputTokens();
        long outputBefore = telemetry.outputTokens();
        AtomicLong firstTokenNanos = new AtomicLong();
        long started = System.nanoTime();
        String answer;
        List<String> productionFailures = List.of();
        List<String> initialFailures = List.of();
        boolean productionRegenerated = false;
        if (testCase.schemaType() == AnswerEvaluationSchemaType.NO_KNOWLEDGE) {
            answer = NO_KNOWLEDGE;
        } else if (testCase.schemaType() == AnswerEvaluationSchemaType.RESOLVED_CASE) {
            ResolvedCaseDraft draft = adapter.generateResolvedCaseDraft(testCase.input());
            answer = draft.title() + "\n" + draft.problem();
        } else {
            List<RetrievalEvidence> evidence = evidence(testCase.evidence());
            GroundedAttempt attempt = groundedAttempt(adapter, testCase, evidence, null,
                    firstTokenNanos);
            if (!attempt.validationFailures().isEmpty()) {
                initialFailures = attempt.validationFailures();
                attempt = groundedAttempt(adapter, testCase, evidence,
                        String.join(",", initialFailures), firstTokenNanos);
                productionRegenerated = true;
            }
            answer = attempt.answer();
            productionFailures = attempt.validationFailures();
        }
        long finished = System.nanoTime();
        AnswerQualityAssessment assessment = quality.evaluate(testCase, answer, true);
        List<String> failures = new ArrayList<>(assessment.failures());
        failures.addAll(productionFailures);
        failures = failures.stream().distinct().toList();
        long firstTokenMs = firstTokenNanos.get() == 0 ? 0
                : nanosToMillis(firstTokenNanos.get() - started);
        return new CaseRun(testCase.caseId(), failures.isEmpty(), failures, initialFailures,
                List.of(), firstTokenMs, nanosToMillis(finished - started),
                telemetry.inputTokens() - inputBefore, telemetry.outputTokens() - outputBefore,
                productionRegenerated, stabilityRerun);
    }

    /** 生成一份完整知识回答并立即执行生产答案校验。 */
    private GroundedAttempt groundedAttempt(DashScopeChatModelAdapter adapter,
                                             AnswerEvaluationCase testCase,
                                             List<RetrievalEvidence> evidence,
                                             String validationFeedback,
                                             AtomicLong firstTokenNanos) {
        String answer = adapter.groundedAnswer(testCase.input(), List.of(), evidence,
                validationFeedback,
                () -> firstTokenNanos.compareAndSet(0, System.nanoTime())).text();
        return new GroundedAttempt(answer, answerValidator.validate(answer, evidence));
    }

    /** 把冻结证据转换成生产 Chat 端口使用的不可变证据对象。 */
    private List<RetrievalEvidence> evidence(List<AnswerEvaluationEvidence> values) {
        ExactTermExtractor extractor = new ExactTermExtractor();
        return values.stream().map(value -> new RetrievalEvidence(
                value.sourceType() + ":" + value.sourceId() + ":1:0", value.sourceType(),
                value.sourceId(), 1, value.title(), value.title(), value.content(),
                extractor.extract(value.content()), Set.of(), null, null, 0.0, 1.0)).toList();
    }

    /** 根据三候选质量结果标记 Max 是否形成必须人工决定的独有收益。 */
    private List<AnswerModelCandidateResult> selectionInputs(List<CandidateRun> runs) {
        boolean flashQualified = runs.stream().filter(value -> value.modelName().contains("flash"))
                .anyMatch(CandidateRun::qualityPassed);
        return runs.stream().map(value -> new AnswerModelCandidateResult(value.modelName(),
                value.qualityPassed(), value.firstRoundAllPassed(), value.stabilityRerunCount(),
                value.completeLatency().p95Ms(), value.totalTokens(),
                value.modelName().contains("max") && value.qualityPassed() && !flashQualified)).toList();
    }

    /** 应用全部质量通过、输入 Token 降低十个百分点且延迟不恶化十个百分点的门槛。 */
    private PromptComparison comparePrompts(CandidateRun original, CandidateRun compact) {
        double tokenReduction = original.inputTokens() == 0 ? 0.0
                : 1.0 - (double) compact.inputTokens() / original.inputTokens();
        double latencyChange = original.completeLatency().p95Ms() == 0 ? 0.0
                : (double) compact.completeLatency().p95Ms() / original.completeLatency().p95Ms() - 1.0;
        boolean adopted = compact.qualityPassed() && tokenReduction >= 0.10 && latencyChange <= 0.10;
        return new PromptComparison(original.promptVariant(), compact.promptVariant(), tokenReduction,
                latencyChange, compact.qualityPassed(), adopted,
                adopted ? "质量全通过、输入 Token 至少降低 10% 且延迟未恶化 10%"
                        : "未同时满足质量、Token 和延迟三项采用门槛");
    }

    /** 返回写入报告的明确模型选择优先级说明。 */
    private List<String> selectionRules() {
        return List.of("事实、引用、精确值、无知识克制和 Schema 是不可加权的质量硬门禁",
                "首轮全部通过优先于因临时故障或偶发格式问题复测后通过",
                "完整回答 P95 相差至少 10% 才视为有效延迟差异",
                "延迟差异不足 10% 时比较总 Token，仍接近时优先 Flash",
                "Max 存在独有质量收益且成本更高时必须由用户决定");
    }

    /** 将不含问题、证据、Prompt、模型正文和密钥的报告写入 target。 */
    private void writeReport(StageEightReport report) throws Exception {
        Path directory = Path.of("target", "stage-8-optimization");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("model-selection-" + report.runId() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** 将非负纳秒向上保留为至少一毫秒的统计值。 */
    private long nanosToMillis(long nanos) {
        return Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    /** 返回真实测试密钥，不把值写入报告或日志。 */
    private String apiKey() { return System.getenv("DASHSCOPE_API_KEY"); }

    /** 返回显式 DashScope 地址，未配置时使用公共原生端点。 */
    private String baseUrl() {
        return System.getenv().getOrDefault("DASHSCOPE_HTTP_BASE_URL",
                "https://dashscope.aliyuncs.com/api/v1");
    }

    /** 捕获单个候选的低基数 Token 统计，不保存正文。 */
    private static final class CapturingTelemetry implements OptimizationTelemetryPort {
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();

        /** 忽略本评测未使用的生产耗时回调。 */
        @Override public void recordDuration(Operation operation, long durationMs,
                                             boolean succeeded) { }

        /** 仅累加 Chat Token，不记录字符对应的输入输出内容。 */
        @Override public void recordUsage(ModelOperation operation, long input, long output,
                                          long itemCount, long characterCount) {
            if (operation == ModelOperation.CHAT) {
                inputTokens.addAndGet(Math.max(0, input));
                outputTokens.addAndGet(Math.max(0, output));
            }
        }

        /** 返回累计输入 Token。 */
        private long inputTokens() { return inputTokens.get(); }

        /** 返回累计输出 Token。 */
        private long outputTokens() { return outputTokens.get(); }

        /** 返回累计输入与输出 Token。 */
        private long totalTokens() { return inputTokens() + outputTokens(); }
    }

    /** 保存单条用例的脱敏门禁、延迟和 Token 统计。 */
    private record CaseRun(String caseId, boolean passed, List<String> failures,
                           List<String> initialValidationFailures,
                           List<String> firstEvaluationFailures,
                           long firstTokenMs, long completeMs, long inputTokens,
                           long outputTokens, boolean productionRegenerated,
                           boolean stabilityRerun) {
        /** 返回保留最终复测结果并记录首轮失败原因的新结果。 */
        private CaseRun withFirstEvaluationFailures(List<String> values) {
            return new CaseRun(caseId, passed, failures, initialValidationFailures,
                    List.copyOf(values), firstTokenMs, completeMs, inputTokens, outputTokens,
                    productionRegenerated, stabilityRerun);
        }
    }

    /** 保存一份完整知识回答及生产校验失败规则。 */
    private record GroundedAttempt(String answer, List<String> validationFailures) { }

    /** 保存一个候选模型的完整脱敏评测摘要。 */
    private record CandidateRun(String modelName, GroundedPromptVariant promptVariant,
                                boolean qualityPassed, boolean firstRoundAllPassed,
                                int productionRegenerationCount, int stabilityRerunCount,
                                boolean tokenWarningReached,
                                long totalTokens, long inputTokens, long outputTokens,
                                LatencySummary firstTokenLatency,
                                LatencySummary completeLatency, List<CaseRun> cases) { }

    /** 保存原始与压缩 Prompt 的三项采用门槛。 */
    private record PromptComparison(GroundedPromptVariant original, GroundedPromptVariant compact,
                                    double inputTokenReduction, double completeP95Change,
                                    boolean compactQualityPassed, boolean adopted, String reason) { }

    /** 保存动态构建产物中的模型选择报告和复现元数据。 */
    private record StageEightReport(String schemaVersion, UUID runId, String gitCommit,
                                    Instant startedAt, Instant finishedAt,
                                    String datasetVersion, String datasetSha256,
                                    List<String> candidateModels, ModelGenerationSettings settings,
                                    long tokenWarning, long tokenHardLimit,
                                    List<CandidateRun> candidates, AnswerModelSelection selection,
                                    CandidateRun promptCandidate,
                                    PromptComparison promptComparison,
                                    List<String> selectionRules) { }
}
