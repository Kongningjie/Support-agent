package com.lawrence.supportagent.memory;

import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** 规范化长期记忆正文并拒绝凭据、个人敏感信息和危险控制字符。 */
public class UserMemoryContentPolicy {
    /** 单条长期记忆允许的最大 Unicode 字符数。 */
    public static final int MAX_CHARACTERS = 500;
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}={0,2}"),
            Pattern.compile("(?i)\\b(?:password|passwd|pwd|api[_-]?key|access[_-]?key|secret[_-]?key|token)\\s*[:=]\\s*\\S{4,}"),
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)"),
            Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"));

    /** 规范化并校验正文，同时返回用于去重的 SHA-256。 */
    public NormalizedMemory normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("记忆正文不能为空");
        }
        String normalized = Normalizer.normalize(value.replace("\r\n", "\n")
                .replace('\r', '\n'), Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > MAX_CHARACTERS) {
            throw new IllegalArgumentException("记忆正文不能超过 500 个字符");
        }
        boolean dangerous = normalized.codePoints().anyMatch(codePoint ->
                (codePoint < 0x20 && codePoint != '\n' && codePoint != '\t')
                        || (codePoint >= 0x7f && codePoint <= 0x9f));
        if (dangerous || FORBIDDEN.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            throw new ApplicationException(ErrorCode.MEMORY_SENSITIVE_CONTENT,
                    "长期记忆包含禁止保存的敏感信息");
        }
        return new NormalizedMemory(normalized, sha256(normalized));
    }

    /** 对规范化正文自身的 UTF-8 字节计算小写十六进制 SHA-256。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /** 规范化正文与不可逆去重哈希。 */
    public record NormalizedMemory(String content, String contentHash) { }
}
