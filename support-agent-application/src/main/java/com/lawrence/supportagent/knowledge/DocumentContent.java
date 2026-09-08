package com.lawrence.supportagent.knowledge;

/** 保存已通过安全校验和平台无关规范化的文档正文及哈希。 */
public record DocumentContent(String normalizedText, String sha256) {
}
