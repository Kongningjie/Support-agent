package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证托管文档规范化、编码、大小和敏感凭据门禁。 */
class DocumentContentPolicyTest {
    private final DocumentContentPolicy policy = new DocumentContentPolicy();

    /** 验证 BOM、换行、Unicode 和首尾空白被确定性规范化。 */
    @Test
    void shouldNormalizeContentDeterministically() {
        DocumentContent result = policy.normalizeDirectText("\uFEFF  Cafe\u0301\r\n第二行  ");

        assertEquals("Café\n第二行", result.normalizedText());
        assertEquals(64, result.sha256().length());
    }

    /** 验证上传内容必须是严格有效的 UTF-8。 */
    @Test
    void shouldRejectMalformedUtf8() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.decodeUploadedFile(new byte[]{(byte) 0xC3, 0x28}));
    }

    /** 验证疑似密钥只返回稳定错误类型而不回显原文。 */
    @Test
    void shouldRejectSensitiveCredential() {
        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> policy.normalizeDirectText("api_key=abcdefghijklmnop"));

        assertEquals(ErrorCode.KNOWLEDGE_SENSITIVE_CONTENT, exception.errorCode());
        assertEquals(false, exception.getMessage().contains("abcdefghijklmnop"));
    }

    /** 验证 UTF-8 字节大小而非 Java 字符数决定正文上限。 */
    @Test
    void shouldEnforceUtf8ByteLimit() {
        String oversized = "中".repeat(DocumentContentPolicy.MAX_BYTES / 3 + 1);

        assertThrows(IllegalArgumentException.class,
                () -> policy.decodeUploadedFile(oversized.getBytes(StandardCharsets.UTF_8)));
    }
}
