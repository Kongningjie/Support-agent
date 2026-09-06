package com.lawrence.supportagent.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 验证请求指纹稳定且不会产生简单字段拼接歧义。 */
class RequestFingerprintTest {
    /** 验证相同字段序列始终生成同一 64 位哈希。 */
    @Test
    void shouldBeDeterministic() {
        String first = RequestFingerprint.sha256("ab", "c", null);
        String second = RequestFingerprint.sha256("ab", "c", null);

        assertEquals(64, first.length());
        assertEquals(first, second);
    }

    /** 验证字段边界不同但直接拼接相同的输入不会碰撞。 */
    @Test
    void shouldPreserveFieldBoundaries() {
        assertNotEquals(RequestFingerprint.sha256("ab", "c"),
                RequestFingerprint.sha256("a", "bc"));
    }
}
