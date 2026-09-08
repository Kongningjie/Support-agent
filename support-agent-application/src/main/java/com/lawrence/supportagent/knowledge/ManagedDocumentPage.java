package com.lawrence.supportagent.knowledge;

import java.util.List;

/** 应用层托管文档分页结果。 */
public record ManagedDocumentPage(List<ManagedDocumentSummary> items, int page, int size,
                                  long totalElements, int totalPages) {
}
