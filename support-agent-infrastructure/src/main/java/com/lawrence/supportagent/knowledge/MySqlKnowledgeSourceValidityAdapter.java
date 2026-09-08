package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 使用 MySQL 业务事实剔除已失效或版本过期的 Elasticsearch 候选。 */
public class MySqlKnowledgeSourceValidityAdapter implements KnowledgeSourceValidityPort {
    private final ManagedDocumentRepository documents;
    private final ResolvedCaseRepository cases;

    /** 注入两类知识来源仓储。 */
    public MySqlKnowledgeSourceValidityAdapter(ManagedDocumentRepository documents,
                                               ResolvedCaseRepository cases) {
        this.documents = documents;
        this.cases = cases;
    }

    /** {@inheritDoc} */
    @Override
    public Set<SourceVersion> findValid(List<SourceVersion> sources) {
        Set<SourceVersion> valid = new HashSet<>();
        for (SourceVersion source : sources) {
            if ("MANAGED_DOCUMENT".equals(source.sourceType())) {
                documents.findById(source.sourceId()).filter(value -> !value.deleted()
                                && value.status() == ManagedDocumentStatus.PUBLISHED
                                && value.version() == source.sourceVersion())
                        .ifPresent(value -> valid.add(source));
            } else if ("RESOLVED_CASE".equals(source.sourceType())) {
                cases.findById(source.sourceId()).filter(value -> !value.deleted()
                                && value.status() == ResolvedCaseStatus.PUBLISHED
                                && value.version() == source.sourceVersion())
                        .ifPresent(value -> valid.add(source));
            }
        }
        return Set.copyOf(valid);
    }
}
