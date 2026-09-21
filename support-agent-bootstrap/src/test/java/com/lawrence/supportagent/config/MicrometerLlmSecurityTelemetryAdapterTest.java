package com.lawrence.supportagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.security.ModelOutputAction;
import com.lawrence.supportagent.security.ModelOutputType;
import com.lawrence.supportagent.security.PromptSecurityAction;
import com.lawrence.supportagent.security.PromptSecuritySignal;
import com.lawrence.supportagent.security.PromptSecuritySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证安全指标仅使用冻结枚举组成的低基数标签。 */
class MicrometerLlmSecurityTelemetryAdapterTest {
    /** 指标不得出现用户、会话、正文、随机标记或错误详情等动态标签。 */
    @Test
    void shouldUseOnlyFrozenLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmSecurityTelemetryAdapter adapter =
                new MicrometerLlmSecurityTelemetryAdapter(registry);

        adapter.recordPromptAssessment(PromptSecuritySource.EVIDENCE,
                PromptSecurityAction.BLOCK, Set.of(PromptSecuritySignal.INSTRUCTION_OVERRIDE));
        adapter.recordOutputAssessment(ModelOutputType.GROUNDED,
                ModelOutputAction.REJECT, List.of("CANARY_LEAK"));
        adapter.recordRegeneration(ModelOutputType.GROUNDED);
        adapter.recordFinalRejection(ModelOutputType.GROUNDED);
        adapter.recordContextExclusion(PromptSecuritySource.EVIDENCE);

        assertThat(registry.getMeters()).hasSize(5);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("source", "action", "signal", "branch", "rule");
                    assertThat(tag.getValue()).doesNotContain("SA-CANARY", "@", "conversation");
                }));
        assertThat(registry.find("support.agent.security.prompt.assessments").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("support.agent.security.output.final.rejections").counter().count())
                .isEqualTo(1.0);
    }
}
