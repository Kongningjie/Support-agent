package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.util.List;

/** 提供托管文档详情、分页和内部任务定位所需的只读边界。 */
public class ManagedDocumentQueryUseCase {
    private final ManagedDocumentRepository repository;

    /** 注入托管文档仓储端口。 */
    public ManagedDocumentQueryUseCase(ManagedDocumentRepository repository) {
        this.repository = repository;
    }

    /** 按公开十进制文档 ID 查询未删除详情。 */
    public ManagedDocumentDetails get(long documentId) {
        return ManagedDocumentDetails.from(requireDocument(documentId));
    }

    /** 按受控状态和关键词查询不含正文的分页摘要。 */
    public ManagedDocumentPage page(ManagedDocumentStatus status, String keyword,
                                    int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page - 1 > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("文档分页参数不合法");
        }
        String normalizedKeyword = normalizeKeyword(keyword);
        long total = repository.count(status, normalizedKeyword);
        List<ManagedDocumentSummary> items = repository.findPage(status, normalizedKeyword,
                (page - 1) * size, size).stream().map(ManagedDocumentSummary::from).toList();
        long pages = total == 0 ? 0 : (total - 1) / size + 1;
        return new ManagedDocumentPage(items, page, size, total,
                pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages);
    }

    /** 按内部 ID 查询可用文档，供幂等重放和任务编排使用。 */
    public ManagedDocument requireDocument(long documentId) {
        if (documentId <= 0) {
            throw new IllegalArgumentException("文档 ID 必须为正整数");
        }
        return repository.findById(documentId)
                .filter(value -> !value.deleted())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.KNOWLEDGE_NOT_FOUND, "托管文档不存在"));
    }

    /** 为查询规整可空关键词并限制长度。 */
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("查询关键词不能超过 160 个字符");
        }
        return normalized;
    }
}
