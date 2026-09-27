package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;
import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceStatus;
import com.lawrence.supportagent.knowledgespace.port.KnowledgeSpaceRepository;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseStatus;
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;
import com.lawrence.supportagent.retrieval.port.KnowledgeSourceValidityPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 使用 MySQL 业务事实剔除已失效或版本过期的 Elasticsearch 候选。 */
public class MySqlKnowledgeSourceValidityAdapter implements KnowledgeSourceValidityPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            MySqlKnowledgeSourceValidityAdapter.class);
    private final ManagedDocumentRepository documents;
    private final ResolvedCaseRepository cases;
    private final KnowledgeSpaceRepository spaces;

    /** 注入两类知识来源仓储。 */
    public MySqlKnowledgeSourceValidityAdapter(ManagedDocumentRepository documents,
                                               ResolvedCaseRepository cases,
                                               KnowledgeSpaceRepository spaces) {
        this.documents = documents;
        this.cases = cases;
        this.spaces = spaces;
    }

    /** {@inheritDoc} */
    @Override
    public Set<SourceVersion> findValid(List<SourceVersion> sources,
                                        Set<UUID> allowedSpaceIds) {
        if (allowedSpaceIds == null || allowedSpaceIds.isEmpty()) {
            throw new IllegalArgumentException("来源回查允许空间不能为空");
        }
        Set<SourceVersion> valid = new HashSet<>();
        int invalidSpaceCount = 0;
        for (SourceVersion source : sources) {
            if (source.spaceId() == null || !allowedSpaceIds.contains(source.spaceId())
                    || spaces.findBySpaceId(source.spaceId())
                    .filter(value -> value.status() == KnowledgeSpaceStatus.ACTIVE).isEmpty()) {
                invalidSpaceCount++;
                continue;
            }
            if ("MANAGED_DOCUMENT".equals(source.sourceType())) {
                documents.findById(source.sourceId()).filter(value -> !value.deleted()
                                && value.status() == ManagedDocumentStatus.PUBLISHED
                                && value.version() == source.sourceVersion()
                                && value.spaceId().equals(source.spaceId()))
                        .ifPresent(value -> valid.add(source));
            } else if ("RESOLVED_CASE".equals(source.sourceType())) {
                cases.findById(source.sourceId()).filter(value -> !value.deleted()
                                && value.status() == ResolvedCaseStatus.PUBLISHED
                                && value.version() == source.sourceVersion()
                                && value.spaceId().equals(source.spaceId()))
                        .ifPresent(value -> valid.add(source));
            }
        }
        if (invalidSpaceCount > 0) {
            LOGGER.warn("Knowledge sources rejected: reason=SOURCE_SPACE_INVALID count={}",
                    invalidSpaceCount);
        }
        return Set.copyOf(valid);
    }
}
