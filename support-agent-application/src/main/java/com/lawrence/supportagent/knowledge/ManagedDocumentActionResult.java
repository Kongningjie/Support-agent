package com.lawrence.supportagent.knowledge;

/** 返回文档状态变化及负责完成外部动作的异步任务。 */
public record ManagedDocumentActionResult(ManagedDocumentDetails document, long taskId) {
}
