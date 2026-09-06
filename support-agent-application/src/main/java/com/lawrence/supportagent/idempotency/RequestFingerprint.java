package com.lawrence.supportagent.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 使用长度分隔的确定性编码生成外部写请求 SHA-256。 */
public final class RequestFingerprint {
    /** 禁止实例化无状态哈希工具。 */
    private RequestFingerprint() {
    }

    /**
     * 按字段顺序和长度生成不产生拼接歧义的 SHA-256。
     *
     * @param values 已按契约顺序排列的可空字段
     * @return 64 位小写十六进制 SHA-256
     */
    public static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
