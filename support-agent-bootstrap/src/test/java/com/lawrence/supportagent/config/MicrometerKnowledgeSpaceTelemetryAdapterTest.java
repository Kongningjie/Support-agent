package com.lawrence.supportagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.Action;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.AllowedSpaceCount;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.RebuildResult;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.RequiredRole;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.Result;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceTelemetryPort.Visibility;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 验证知识空间指标不接受空间、用户或正文等高基数标签。 */
class MicrometerKnowledgeSpaceTelemetryAdapterTest {
    /** 指标只能包含冻结分类和布尔值。 */
    @Test
    void shouldUseOnlyLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerKnowledgeSpaceTelemetryAdapter adapter =
                new MicrometerKnowledgeSpaceTelemetryAdapter(registry);

        adapter.recordAccess(Action.ROLE_CHECK, Result.DENIED, RequiredRole.MANAGER,
                Visibility.RESTRICTED, false, "ROLE_INSUFFICIENT");
        adapter.recordRetrievalScope(AllowedSpaceCount.TWO);
        adapter.recordRebuild(RebuildResult.SUCCEEDED);

        assertThat(registry.getMeters()).hasSize(3);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("action", "result", "required_role",
                            "visibility", "active_global", "reason", "allowed_count");
                    assertThat(tag.getValue()).doesNotContain(
                            "00000000-", "TEST_ALPHA", "member", "query");
                }));
        assertThat(registry.find("support.agent.knowledge.space.access").counter().count())
                .isEqualTo(1D);
    }
}
