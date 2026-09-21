package com.lawrence.supportagent.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证输出安全策略异常时不会把未经检查的正文当作通过结果。 */
class ModelOutputSecurityServiceFailureTest {
    /** 策略异常必须向上失败，禁止降级为 PASS。 */
    @Test
    void shouldFailClosedWhenOutputPolicyFails() {
        ModelOutputSecurityService service = new ModelOutputSecurityService(request -> {
            throw new IllegalStateException("policy unavailable");
        }, new LlmSecuritySettings(true, true, true, true, 1), UUID::randomUUID);

        assertThatThrownBy(() -> service.assess(ModelOutputType.GREETING,
                "未经检查的正文", service.newInvocation(List.of()), "你好", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
