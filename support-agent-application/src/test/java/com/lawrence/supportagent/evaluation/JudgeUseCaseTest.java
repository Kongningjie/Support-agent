package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证Judge盲评、幂等、坏输出失败关闭、预算和人工对照，不调用网络。 */
class JudgeUseCaseTest {
    private final Instant now=Instant.parse("2026-10-13T00:00:00Z");
    private final UUID sampleId=UUID.randomUUID(), key=UUID.randomUUID();
    private final AuthenticatedUser admin=new AuthenticatedUser(UUID.randomUUID(),"admin",UserRole.ADMIN);
    private final HumanEvaluationUseCase humans=mock(HumanEvaluationUseCase.class);
    private final JudgeRunRepository runs=mock(JudgeRunRepository.class);
    private final JudgeModelPort model=mock(JudgeModelPort.class);
    private final AtomicReference<JudgeRunRepository.Run> stored=new AtomicReference<>();
    private JudgeUseCase useCase;
    /** 用短事务替身记录真实用例产生的状态，模型结果独立提供。 */
    @BeforeEach void setup() {
        useCase=new JudgeUseCase(humans,runs,model,()->now);
        when(humans.details(admin,sampleId)).thenReturn(detail(false,"问题"));
        when(model.modelName()).thenReturn("judge-test");
        when(model.promptVersion()).thenReturn("hash:schema-v1");
        when(model.evaluate(any())).thenReturn(result(2));
        when(runs.find(admin.userId(),key)).thenAnswer(ignored->Optional.ofNullable(stored.get()));
        doAnswer(invocation->{ stored.set(invocation.getArgument(0)); return null; }).when(runs).claim(any());
        doAnswer(invocation->{
            var value=stored.get();
            List<JudgeRunRepository.Attempt> attempts=invocation.getArgument(1);
            stored.set(new JudgeRunRepository.Run(value.id(),value.sampleId(),value.actorId(),value.key(),value.model(),
                    value.promptVersion(),"COMPLETED",value.estimatedInputTokens(),value.startedAt(),value.expiresAt(),attempts));
            return null;
        }).when(runs).complete(any(),anyList(),any());
    }
    /** 未决期间不泄露Judge评分；重复Key只返回运行。 */
    @Test void shouldHideScoresAndNotRepeatCalls() {
        var first=useCase.execute(admin,sampleId,key);
        assertThat(first.hiddenUntilHumanDecision()).isTrue();
        assertThat(first.attempts()).isEmpty();
        useCase.execute(admin,sampleId,key);
        verify(model,times(3)).evaluate(any());
        verify(runs,times(1)).claim(any());
    }
    /** 人工已决时按五维对照并检测复测一致。 */
    @Test void shouldCompareOnlyWithFinalHumanScore() {
        when(humans.details(admin,sampleId)).thenReturn(detail(true,"问题"));
        var value=useCase.execute(admin,sampleId,key);
        assertThat(value.meanAbsoluteErrors()).containsExactly(0d,0d,0d,0d,0d);
        assertThat(value.exactAgreementRates()).containsExactly(1d,1d,1d,1d,1d);
        assertThat(value.repeatStable()).isTrue();
        assertThat(value.attempts()).hasSize(3);
    }
    /** 敏感理由不进入持久化，也不能作为零分伪造有效结论。 */
    @Test void shouldRejectSensitiveReason() {
        var dimension=new JudgeModelPort.Dimension(2,"api_key=secret-value",null,null);
        when(model.evaluate(any())).thenReturn(new JudgeModelPort.Result(Collections.nCopies(5,dimension),null,null));
        useCase.execute(admin,sampleId,key);
        assertThat(stored.get().attempts()).allSatisfy(item->{
            assertThat(item.result()).isNull(); assertThat(item.failure()).isEqualTo("INVALID_OUTPUT");
        });
    }
    /** 小数、缺项或越界评分不能由应用用例接受。 */
    @Test void shouldRejectOutOfRangeScore() {
        when(model.evaluate(any())).thenReturn(result(3));
        useCase.execute(admin,sampleId,key);
        assertThat(stored.get().attempts()).allMatch(item->item.result()==null);
    }
    /** 证据区间必须按Unicode字符落在允许查看的证据内。 */
    @Test void shouldRejectInvalidEvidenceLocation() {
        when(model.evaluate(any())).thenReturn(new JudgeModelPort.Result(
                Collections.nCopies(5,new JudgeModelPort.Dimension(2,"理由",0,100)),null,null));
        useCase.execute(admin,sampleId,key);
        assertThat(stored.get().attempts()).allMatch(item->"INVALID_OUTPUT".equals(item.failure()));
    }
    /** 模型失败仅记录分类，无重试、无原始错误泄漏。 */
    @Test void shouldRecordPartialFailureAndUnknownStability() {
        when(humans.details(admin,sampleId)).thenReturn(detail(true,"问题"));
        when(model.evaluate(any())).thenReturn(result(2)).thenThrow(new IllegalStateException("内部模型错误")).thenReturn(result(2));
        var value=useCase.execute(admin,sampleId,key);
        assertThat(value.repeatStable()).isNull();
        assertThat(value.attempts().get(1).failure()).isEqualTo("MODEL_FAILURE");
        verify(model,times(3)).evaluate(any());
    }
    /** 预算超限必须在占用及模型调用之前拒绝。 */
    @Test void shouldRejectOversizedInput() {
        when(humans.details(admin,sampleId)).thenReturn(detail(false,"中".repeat(8000)));
        assertThatThrownBy(()->useCase.execute(admin,sampleId,key)).isInstanceOf(IllegalArgumentException.class);
        verify(runs,never()).claim(any()); verify(model,never()).evaluate(any());
    }
    /** XML转义会放大输入，不能把原文字数低估成可调用预算。 */
    @Test void shouldBudgetEscapedInput() {
        when(humans.details(admin,sampleId)).thenReturn(detail(false,"<".repeat(8000)));
        assertThatThrownBy(()->useCase.execute(admin,sampleId,key)).isInstanceOf(IllegalArgumentException.class);
        verify(model,never()).evaluate(any());
    }
    /** 无模型时拒绝真实评测入口。 */
    @Test void shouldRejectUnconfiguredModel() {
        when(model.modelName()).thenReturn("");
        assertThatThrownBy(()->useCase.execute(admin,sampleId,key)).isInstanceOf(RuntimeException.class);
        verify(model,never()).evaluate(any());
    }
    /** 已过期幂等请求返回失败但不能重新调用。 */
    @Test void shouldNotRerunExpiredRequest() {
        stored.set(new JudgeRunRepository.Run(UUID.randomUUID(),sampleId,admin.userId(),key,"judge","v1",
                "RUNNING",20,now.minusSeconds(300),now.minusSeconds(1),List.of()));
        assertThat(useCase.execute(admin,sampleId,key).status()).isEqualTo("FAILED");
        verify(model,never()).evaluate(any());
    }
    /** 校准对同一样本去重，三次复测不计作三份真值。 */
    @Test void shouldRequireThirtyDistinctFullyValidSamples() {
        when(humans.details(eq(admin),any())).thenReturn(detail(true,"问题"));
        var attempt = new JudgeRunRepository.Attempt(1,result(2),null,1);
        var run = new JudgeRunRepository.Run(UUID.randomUUID(),sampleId,admin.userId(),key,"judge","v1",
                "COMPLETED",10,now,now.plusSeconds(240),List.of(attempt,attempt,attempt));
        when(runs.calibrationRuns()).thenReturn(Collections.nCopies(30,run));
        var report = useCase.report(admin).getFirst();
        assertThat(report.sampleCount()).isEqualTo(1);
        assertThat(report.qualityThresholdPassed()).isFalse();
        assertThat(report.sourceModelBiasUnverified()).isTrue();
    }
    /** 三十份独立样本满足数值门槛；另一模板版本必须单独验收。 */
    @Test void shouldKeepCalibrationVersionsSeparate() {
        when(humans.details(eq(admin),any())).thenReturn(detail(true,"问题"));
        List<JudgeRunRepository.Run> rows=new ArrayList<>();
        var attempt=new JudgeRunRepository.Attempt(1,result(2),null,1);
        for (int index=0;index<31;index++) {
            rows.add(new JudgeRunRepository.Run(UUID.randomUUID(),UUID.randomUUID(),admin.userId(),UUID.randomUUID(),
                    "judge",index==30?"v2":"v1","COMPLETED",10,now,now.plusSeconds(240),List.of(attempt,attempt,attempt)));
        }
        when(runs.calibrationRuns()).thenReturn(rows);
        var reports=useCase.report(admin);
        assertThat(reports).hasSize(2);
        assertThat(reports.stream().filter(value->value.promptVersion().equals("v1")).findFirst().orElseThrow()
                .qualityThresholdPassed()).isTrue();
        assertThat(reports.stream().filter(value->value.promptVersion().equals("v2")).findFirst().orElseThrow()
                .qualityThresholdPassed()).isFalse();
    }
    /** 同进程并发入口不排队，不产生第二组模型调用。 */
    @Test void shouldRejectConcurrentExecution() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(model.evaluate(any())).thenAnswer(ignored->{ entered.countDown();
            if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时");
            return result(2); });
        try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->useCase.execute(admin,sampleId,key));
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->useCase.execute(admin,sampleId,UUID.randomUUID())).isInstanceOf(RuntimeException.class);
            } finally { release.countDown(); }
            future.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(model,times(3)).evaluate(any());
    }
    /** 构造固定已脱敏样本及可空人工最终分。 */
    private HumanEvaluationUseCase.Detail detail(boolean decided,String question) {
        var sample=new HumanEvaluationRepository.Sample(sampleId,"FIXED","case",UUID.randomUUID(),question,"回答","证据",
                admin.userId(),decided?HumanEvaluationRepository.ReviewStatus.DECIDED:HumanEvaluationRepository.ReviewStatus.FIRST_PENDING,
                HumanEvaluationRepository.DatasetState.NONE,0,now);
        return new HumanEvaluationUseCase.Detail(sample,List.of(),decided?new HumanEvaluationRepository.Score(2,2,2,2,2):null);
    }
    /** 五维结构化测试结果，不涉及任何在线模型。 */
    private JudgeModelPort.Result result(int score) {
        return new JudgeModelPort.Result(Collections.nCopies(5,new JudgeModelPort.Dimension(score,"简短理由",null,null)),10L,20L);
    }
}
