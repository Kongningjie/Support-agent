package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ConservativeTokenEstimator;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** 在问答链路之外执行三次独立模型评审，保护人工盲评和调用幂等。 */
public class JudgeUseCase {
    public static final String PROMPT_VERSION = "judge-v1";
    private final HumanEvaluationUseCase humans;
    private final JudgeRunRepository runs;
    private final JudgeModelPort model;
    private final TimeProvider time;
    private final Semaphore capacity = new Semaphore(1);

    /** 注入脱敏样本权限边界、持久化、独立模型及 UTC 时钟。 */
    public JudgeUseCase(HumanEvaluationUseCase humans, JudgeRunRepository runs,
                        JudgeModelPort model, TimeProvider time) {
        this.humans = humans; this.runs = runs; this.model = model; this.time = time;
    }

    /** 显式评审一份样本；模型调用不在事务内，相同 Key 不重新调用。 */
    public View execute(AuthenticatedUser actor, UUID sampleId, UUID key) {
        var detail = humans.details(actor, sampleId);
        if (key == null) throw new IllegalArgumentException("评测幂等标识不能为空");
        var existing = runs.find(actor.userId(), key);
        if (existing.isPresent()) {
            if (!sampleId.equals(existing.get().sampleId())) throw conflict("同一幂等标识不能用于不同样本");
            return view(existing.get(), detail);
        }
        if (model.modelName() == null || model.modelName().isBlank()) {
            throw conflict("Judge 未开启或未配置评审模型");
        }
        if (!capacity.tryAcquire()) throw conflict("已有模型评测执行中，请稍后重试");
        try {
            var sample = detail.sample();
            int estimate = new ConservativeTokenEstimator().estimate(List.of(sample.question(),
                    sample.answer(), sample.evidence()).stream().map(this::escapeForBudget).toList()) + 1500;
            if (estimate > 8000) throw new IllegalArgumentException("评测输入超过 8000 Token 预估预算");
            var now = time.now();
            var run = new JudgeRunRepository.Run(UUID.randomUUID(), sampleId, actor.userId(), key,
                    model.modelName(), model.promptVersion(), "RUNNING", estimate, now,
                    now.plusSeconds(240), List.of());
            runs.claim(run);
            List<JudgeRunRepository.Attempt> attempts = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                long start = System.nanoTime();
                try {
                    var result = model.evaluate(sample);
                    validate(result, sample.evidence());
                    attempts.add(new JudgeRunRepository.Attempt(index, result, null, elapsed(start)));
                } catch (RuntimeException exception) {
                    attempts.add(new JudgeRunRepository.Attempt(index, null,
                            exception instanceof IllegalArgumentException ? "INVALID_OUTPUT" : "MODEL_FAILURE",
                            elapsed(start)));
                }
            }
            runs.complete(run.id(), List.copyOf(attempts), time.now());
            return view(runs.find(actor.userId(), key).orElseThrow(), humans.details(actor, sampleId));
        } finally { capacity.release(); }
    }

    /** 未决时只返回运行元数据，不返回 Judge 分数、理由或对照。 */
    public List<View> list(AuthenticatedUser actor, UUID sampleId) {
        var detail = humans.details(actor, sampleId);
        return runs.list(sampleId).stream().map(run -> view(run, detail)).toList();
    }

    /** 校验所有维度、证据区间与数值用量，拒绝无依据的范围及缺项。 */
    private void validate(JudgeModelPort.Result result, String evidence) {
        if (result == null || result.dimensions().size() != 5) throw new IllegalArgumentException("Judge 缺少五维");
        int length = evidence.codePointCount(0, evidence.length());
        for (var dimension : result.dimensions()) {
            if (dimension == null || dimension.score() < 0 || dimension.score() > 2
                    || dimension.reason() == null || dimension.reason().isBlank()
                    || dimension.reason().length() > 1000) throw new IllegalArgumentException("Judge 维度无效");
            try { new com.lawrence.supportagent.memory.UserMemoryContentPolicy().normalize(dimension.reason()); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("Judge 理由包含敏感内容或过长"); }
            Integer start = dimension.evidenceStart(), end = dimension.evidenceEnd();
            if ((start == null) != (end == null) || (start != null
                    && (start < 0 || end <= start || end > length))) throw new IllegalArgumentException("Judge 证据位置无效");
        }
        if ((result.inputTokens() != null && result.inputTokens() < 0)
                || (result.outputTokens() != null && result.outputTokens() < 0)) {
            throw new IllegalArgumentException("Judge 用量无效");
        }
    }

    /** 投影三次复测与人工逐维误差；稳定性只有三次有效时才给出。 */
    private View view(JudgeRunRepository.Run run, HumanEvaluationUseCase.Detail detail) {
        boolean decided = detail.finalScore() != null;
        List<JudgeRunRepository.Attempt> visible = decided ? run.attempts() : List.of();
        var valid = visible.stream().filter(item -> item.result() != null).toList();
        List<Double> errors = new ArrayList<>();
        List<Double> agreements = new ArrayList<>();
        List<Double> signedErrors = new ArrayList<>();
        if (!valid.isEmpty()) {
            var truth = detail.finalScore();
            int[] scores = { truth.factual(), truth.citation(), truth.completeness(), truth.actionability(), truth.clarity() };
            for (int index = 0; index < 5; index++) {
                int dimension = index;
                errors.add(valid.stream().mapToInt(item -> Math.abs(item.result().dimensions().get(dimension).score()
                        - scores[dimension])).average().orElseThrow());
                agreements.add(valid.stream().filter(item -> item.result().dimensions().get(dimension).score()
                        == scores[dimension]).count() / (double) valid.size());
                signedErrors.add(valid.stream().mapToInt(item -> item.result().dimensions().get(dimension).score()
                        - scores[dimension]).average().orElseThrow());
            }
        }
        Boolean stable = valid.size() == 3 ? valid.getFirst().result().dimensions().stream()
                .map(JudgeModelPort.Dimension::score).toList().equals(valid.get(1).result().dimensions().stream()
                        .map(JudgeModelPort.Dimension::score).toList())
                && valid.getFirst().result().dimensions().stream().map(JudgeModelPort.Dimension::score).toList()
                        .equals(valid.get(2).result().dimensions().stream().map(JudgeModelPort.Dimension::score).toList()) : null;
        String status = "RUNNING".equals(run.status()) && !run.expiresAt().isAfter(time.now()) ? "FAILED" : run.status();
        return new View(run.id(), run.sampleId(), run.key(), run.model(), run.promptVersion(), status,
                run.estimatedInputTokens(), visible, List.copyOf(errors), List.copyOf(agreements),
                List.copyOf(signedErrors), stable, !decided);
    }

    /** 按模型与模板版本分别校准；每份样本只取最新运行，复测不是独立样本。 */
    public List<Calibration> report(AuthenticatedUser actor) {
        humans.list(actor, 1, 1);
        var rows = runs.calibrationRuns();
        boolean truncated = rows.size() > 1000;
        java.util.Map<String, JudgeRunRepository.Run> latest = new java.util.LinkedHashMap<>();
        rows.stream().limit(1000).forEach(run -> latest.putIfAbsent(
                run.sampleId() + ":" + run.model() + ":" + run.promptVersion(), run));
        java.util.Map<String, List<View>> groups = new java.util.LinkedHashMap<>();
        for (var run : latest.values()) {
            var value = view(run, humans.details(actor, run.sampleId()));
            groups.computeIfAbsent(run.model() + ":" + run.promptVersion(), ignored -> new ArrayList<>()).add(value);
        }
        List<Calibration> reports = new ArrayList<>();
        for (var group : groups.values()) {
            var valid = group.stream().filter(value -> value.repeatStable() != null).toList();
            List<Double> mae = new ArrayList<>(), agreement = new ArrayList<>();
            for (int index = 0; index < 5 && !valid.isEmpty(); index++) {
                int dimension = index;
                mae.add(valid.stream().mapToDouble(value -> value.meanAbsoluteErrors().get(dimension)).average().orElseThrow());
                agreement.add(valid.stream().mapToDouble(value -> value.exactAgreementRates().get(dimension)).average().orElseThrow());
            }
            Double stable = valid.isEmpty() ? null : valid.stream().filter(value -> Boolean.TRUE.equals(value.repeatStable())).count() / (double) valid.size();
            boolean pass = !truncated && valid.size() >= 30 && mae.stream().allMatch(value -> value <= 0.3)
                    && agreement.stream().allMatch(value -> value >= 0.8) && stable != null && stable >= 0.9;
            var first = group.getFirst();
            reports.add(new Calibration(first.model(), first.promptVersion(), group.size(), valid.size(),
                    List.copyOf(mae), List.copyOf(agreement), stable, pass, truncated, true, List.copyOf(group)));
        }
        return List.copyOf(reports);
    }

    /** 将单调时钟转换为安全的毫秒耗时。 */
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    /** 预算必须包含XML转义扩张；固定模板和协议另预留1500 Token。 */
    private String escapeForBudget(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    /** 返回不泄露底层异常的可恢复冲突。 */
    private ApplicationException conflict(String message) { return new ApplicationException(ErrorCode.COMMON_CONFLICT, message); }
    /** 公开运行视图；五维数组顺序固定，sourceModel 未知，不能宣称独立供应商对比。 */
    public record View(UUID runId, UUID sampleId, UUID idempotencyKey, String model, String promptVersion, String status,
                       int estimatedInputTokens, List<JudgeRunRepository.Attempt> attempts,
                       List<Double> meanAbsoluteErrors, List<Double> exactAgreementRates,
                       List<Double> signedMeanErrors,
                       Boolean repeatStable, boolean hiddenUntilHumanDecision) { }
    /** 各版本人工一致性报告；样本不足、截断及同源偏差未验证必须明确显示。 */
    public record Calibration(String model, String promptVersion, int sampleCount, int fullyValidSampleCount,
                              List<Double> meanAbsoluteErrors, List<Double> exactAgreementRates,
                              Double repeatStabilityRate, boolean qualityThresholdPassed, boolean truncated,
                              boolean sourceModelBiasUnverified, List<View> samples) { }
}
