package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** 统一执行文档大小、UTF-8、控制字符、规范化、哈希和敏感凭据校验。 */
public class DocumentContentPolicy {
    /** 一期允许的文档最大 UTF-8 字节数。 */
    public static final int MAX_BYTES = 1024 * 1024;
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}={0,2}"),
            Pattern.compile("(?i)\\bpassword\\s*=\\s*[^\\s;]{4,}"),
            Pattern.compile("(?i)jdbc:[^\\s]+://[^\\s/:]+:[^\\s@]+@[^\\s]+"),
            Pattern.compile("(?i)\\b(?:api[_-]?key|access[_-]?key|secret[_-]?key)\\s*[:=]\\s*[A-Za-z0-9_./+-]{12,}"));

    /** 校验并规范化直接文本。 */
    public DocumentContent normalizeDirectText(String content) {
        if (content == null) {
            throw new IllegalArgumentException("文档正文不能为空");
        }
        return normalize(content);
    }

    /** 严格按 UTF-8 解码上传字节后执行统一内容策略。 */
    public DocumentContent decodeUploadedFile(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("上传文件不能超过 1 MiB");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return normalize(decoded);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("上传文件必须使用有效 UTF-8 编码");
        }
    }

    /** 对已经持久化的正文重新执行敏感凭据扫描。 */
    public void verifyNoSensitiveContent(String normalizedText) {
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            if (pattern.matcher(normalizedText).find()) {
                throw new ApplicationException(ErrorCode.KNOWLEDGE_SENSITIVE_CONTENT,
                        "文档包含疑似凭据，不能保存或发布");
            }
        }
    }

    /** 执行平台无关规范化、正文安全检查和 SHA-256 计算。 */
    private DocumentContent normalize(String value) {
        String withoutBom = value.startsWith("\uFEFF") ? value.substring(1) : value;
        String normalized = Normalizer.normalize(withoutBom.replace("\r\n", "\n")
                .replace('\r', '\n'), Normalizer.Form.NFC).strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("文档正文不能为空");
        }
        byte[] utf8 = normalized.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_BYTES) {
            throw new IllegalArgumentException("文档正文不能超过 1 MiB UTF-8 字节");
        }
        rejectDangerousControls(normalized);
        verifyNoSensitiveContent(normalized);
        return new DocumentContent(normalized, sha256(utf8));
    }

    /** 拒绝具有二进制或终端控制语义的危险控制字符。 */
    private void rejectDangerousControls(String value) {
        boolean dangerous = value.codePoints().anyMatch(codePoint ->
                (codePoint < 0x20 && codePoint != '\n' && codePoint != '\t')
                        || (codePoint >= 0x7f && codePoint <= 0x9f)
                        || codePoint == 0xfffe || codePoint == 0xffff
                        || (codePoint >= 0xd800 && codePoint <= 0xdfff));
        if (dangerous) {
            throw new IllegalArgumentException("文档包含不允许的控制字符或无效字符");
        }
    }

    /** 计算规范化 UTF-8 正文的小写十六进制 SHA-256。 */
    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
