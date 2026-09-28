package com.lawrence.supportagent.knowledgespace;

/** 记录不包含空间、用户或正文标识的知识空间低基数指标。 */
public interface KnowledgeSpaceTelemetryPort {
    /** 空间访问动作。 */
    enum Action { READ, ROLE_CHECK, RETRIEVAL_CONTEXT, LIST }

    /** 空间访问判定结果。 */
    enum Result { ALLOWED, DENIED }

    /** 操作要求的最小角色类别。 */
    enum RequiredRole { READ, READER, EDITOR, MANAGER, ADMIN }

    /** 不暴露具体空间的可见性类别。 */
    enum Visibility { ENTERPRISE, RESTRICTED, UNKNOWN }

    /** 检索允许空间数量区间。 */
    enum AllowedSpaceCount { NONE, ONE, TWO, MORE }

    /** 索引重建的稳定结果。 */
    enum RebuildResult { SUCCEEDED, FAILED }

    /** 记录一次空间访问判定。 */
    void recordAccess(Action action, Result result, RequiredRole requiredRole,
                      Visibility visibility, boolean globalSpace, String reason);

    /** 记录一次检索上下文允许空间数量区间。 */
    void recordRetrievalScope(AllowedSpaceCount count);

    /** 记录一次知识索引全量重建结果。 */
    void recordRebuild(RebuildResult result);

    /** 返回供隔离测试使用的无副作用实现。 */
    static KnowledgeSpaceTelemetryPort noOp() {
        return new KnowledgeSpaceTelemetryPort() {
            /** {@inheritDoc} */
            @Override public void recordAccess(Action action, Result result,
                    RequiredRole requiredRole, Visibility visibility,
                    boolean globalSpace, String reason) { }
            /** {@inheritDoc} */
            @Override public void recordRetrievalScope(AllowedSpaceCount count) { }
            /** {@inheritDoc} */
            @Override public void recordRebuild(RebuildResult result) { }
        };
    }
}
