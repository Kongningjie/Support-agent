package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.evaluation.KnowledgeSpaceEvaluationProbePort.Observation;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 执行固定空间隔离评测、计算冻结门槛并写出不含正文的动态报告。 */
public class KnowledgeSpaceEvaluationService {
    private final KnowledgeSpaceEvaluationDatasetPort dataset;
    private final KnowledgeSpaceEvaluationProbePort probe;
    private final KnowledgeSpaceEvaluationReportPort reports;
    private final UuidGenerator ids;
    private final TimeProvider time;
    private final double lockedBaselineRecallAtFive;

    /** 注入固定数据集、受控运行器、报告端口和四期前 Recall@5 基线。 */
    public KnowledgeSpaceEvaluationService(KnowledgeSpaceEvaluationDatasetPort dataset,
                                           KnowledgeSpaceEvaluationProbePort probe,
                                           KnowledgeSpaceEvaluationReportPort reports,
                                           UuidGenerator ids, TimeProvider time,
                                           double lockedBaselineRecallAtFive) {
        if (lockedBaselineRecallAtFive < 0 || lockedBaselineRecallAtFive > 1) {
            throw new IllegalArgumentException("Recall@5 基线必须位于 0 到 1");
        }
        this.dataset = dataset;
        this.probe = probe;
        this.reports = reports;
        this.ids = ids;
        this.time = time;
        this.lockedBaselineRecallAtFive = lockedBaselineRecallAtFive;
    }

    /** 运行全部固定场景并仅在计算完成后写出报告。 */
    public KnowledgeSpaceEvaluationReport run() {
        List<KnowledgeSpaceEvaluationCase> cases = dataset.load();
        Accumulator values = new Accumulator();
        List<String> failed = new ArrayList<>();
        for (KnowledgeSpaceEvaluationCase testCase : cases) {
            Observation observation = probe.evaluate(testCase);
            if (!evaluate(testCase, observation, values)) {
                failed.add(testCase.caseId());
            }
        }
        double permissionRate = ratio(values.permissionRejected, values.permissionCases);
        double recallAtFive = ratio(values.recalledSources, values.expectedSources);
        double noHitAccuracy = ratio(values.correctNoHit, values.noHitCases);
        double exactTermRecall = ratio(values.returnedExactTerms, values.requiredExactTerms);
        boolean passed = values.crossSpaceRerank == 0 && values.crossSpaceReferences == 0
                && values.crossSpaceFacts == 0 && values.detailLeaks == 0
                && permissionRate == 1D && recallAtFive >= lockedBaselineRecallAtFive
                && noHitAccuracy == 1D && exactTermRecall == 1D
                && values.enumerationLeaks == 0 && failed.isEmpty();
        KnowledgeSpaceEvaluationReport report = new KnowledgeSpaceEvaluationReport(
                ids.generate(), time.now(), cases.size(), values.crossSpaceRerank,
                values.crossSpaceReferences, values.crossSpaceFacts, values.detailLeaks,
                permissionRate, recallAtFive, lockedBaselineRecallAtFive, noHitAccuracy,
                exactTermRecall, values.enumerationLeaks, List.copyOf(failed), passed);
        reports.write(report);
        return report;
    }

    /** 聚合单条用例结果并返回该用例是否满足预期。 */
    private boolean evaluate(KnowledgeSpaceEvaluationCase testCase, Observation observation,
                             Accumulator values) {
        Set<String> forbidden = Set.copyOf(testCase.forbiddenSources());
        long rerankLeaks = intersection(observation.rerankSources(), forbidden);
        long referenceLeaks = intersection(observation.citationSources(), forbidden);
        long factLeaks = intersection(observation.answerFactSources(), forbidden);
        long detailLeaks = intersection(observation.detailSources(), forbidden);
        values.crossSpaceRerank += rerankLeaks;
        values.crossSpaceReferences += referenceLeaks;
        values.crossSpaceFacts += factLeaks;
        values.detailLeaks += detailLeaks;
        if (testCase.category() == KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE) {
            values.permissionCases++;
            if (observation.permissionRejected()) values.permissionRejected++;
        }
        Set<String> topFive = new HashSet<>(observation.topFiveSources());
        values.expectedSources += testCase.expectedSources().size();
        values.recalledSources += testCase.expectedSources().stream().filter(topFive::contains).count();
        if ("NO_HIT".equals(testCase.expectedResult())) {
            values.noHitCases++;
            if (observation.noHit()) values.correctNoHit++;
        }
        Set<String> exactTerms = new HashSet<>(observation.returnedExactTerms());
        values.requiredExactTerms += testCase.requiredExactTerms().size();
        values.returnedExactTerms += testCase.requiredExactTerms().stream()
                .filter(exactTerms::contains).count();
        long enumerationLeaks = testCase.forbiddenDisclosures().stream()
                .filter(value -> observation.safeResponse() != null
                        && observation.safeResponse().contains(value)).count();
        values.enumerationLeaks += enumerationLeaks;
        boolean permissionMatches = testCase.category() != KnowledgeSpaceEvaluationCategory.PERMISSION_CHANGE
                || observation.permissionRejected();
        boolean noHitMatches = !"NO_HIT".equals(testCase.expectedResult()) || observation.noHit();
        return rerankLeaks + referenceLeaks + factLeaks + detailLeaks + enumerationLeaks == 0
                && permissionMatches && noHitMatches
                && testCase.expectedSources().stream().allMatch(topFive::contains)
                && testCase.requiredExactTerms().stream().allMatch(exactTerms::contains);
    }

    /** 计算观测来源与禁止来源的交集数量。 */
    private long intersection(List<String> actual, Set<String> forbidden) {
        return actual.stream().filter(forbidden::contains).distinct().count();
    }

    /** 安全计算比率；无适用样本时视为完全满足。 */
    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1D : (double) numerator / denominator;
    }

    /** 保存评测运行期间的内部计数，不对外暴露。 */
    private static final class Accumulator {
        private long crossSpaceRerank;
        private long crossSpaceReferences;
        private long crossSpaceFacts;
        private long detailLeaks;
        private long permissionCases;
        private long permissionRejected;
        private long expectedSources;
        private long recalledSources;
        private long noHitCases;
        private long correctNoHit;
        private long requiredExactTerms;
        private long returnedExactTerms;
        private long enumerationLeaks;
    }
}
