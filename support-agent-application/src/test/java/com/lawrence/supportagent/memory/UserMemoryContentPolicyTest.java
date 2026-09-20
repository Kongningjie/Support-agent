package com.lawrence.supportagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import org.junit.jupiter.api.Test;

/** 验证长期记忆规范化、长度和敏感信息门禁。 */
class UserMemoryContentPolicyTest {
    private final UserMemoryContentPolicy policy = new UserMemoryContentPolicy();

    /** 规范化等价正文必须生成相同内容和哈希。 */
    @Test void shouldNormalizeStableContent() {
        var left = policy.normalize("  使用 PowerShell 7\r\n执行命令  ");
        var right = policy.normalize("使用 PowerShell 7\n执行命令");
        assertEquals(right, left);
    }

    /** 正文去重哈希必须是规范化正文自身的标准 SHA-256，而不是请求指纹编码。 */
    @Test void shouldHashNormalizedContentDirectly() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                policy.normalize("abc").contentHash());
    }

    /** 凭据和个人敏感信息不得进入长期记忆。 */
    @Test void shouldRejectSensitiveContent() {
        assertThrows(ApplicationException.class,
                () -> policy.normalize("api_key=abcdefghijklmnop"));
        assertThrows(ApplicationException.class,
                () -> policy.normalize("我的手机号是 13812345678"));
        assertThrows(ApplicationException.class,
                () -> policy.normalize("联系邮箱 user@example.com"));
    }
}
