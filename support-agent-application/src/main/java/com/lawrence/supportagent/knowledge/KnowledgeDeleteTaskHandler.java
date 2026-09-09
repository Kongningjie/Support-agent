package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.asynctask.AggregateType;
import com.lawrence.supportagent.asynctask.AsyncTask;
import com.lawrence.supportagent.asynctask.AsyncTaskBusinessMutation;
import com.lawrence.supportagent.asynctask.AsyncTaskCancelledException;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionContext;
import com.lawrence.supportagent.asynctask.AsyncTaskExecutionException;
import com.lawrence.supportagent.asynctask.AsyncTaskHandler;
import com.lawrence.supportagent.asynctask.AsyncTaskType;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexException;
import com.lawrence.supportagent.knowledge.port.KnowledgeIndexPort;
import com.lawrence.supportagent.knowledge.port.ManagedDocumentRepository;

/** 删除已归档托管文档在 Elasticsearch 中的全部历史分块。 */
public class KnowledgeDeleteTaskHandler implements AsyncTaskHandler {
    private static final String SOURCE_TYPE = "MANAGED_DOCUMENT";
    private final ManagedDocumentRepository documentRepository;
    private final KnowledgeIndexPort indexPort;

    /** 注入托管文档和 Elasticsearch 索引端口。 */
    public KnowledgeDeleteTaskHandler(ManagedDocumentRepository documentRepository,
                                      KnowledgeIndexPort indexPort) {
        this.documentRepository = documentRepository;
        this.indexPort = indexPort;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskType taskType() {
        return AsyncTaskType.KNOWLEDGE_DELETE;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supports(AsyncTask task) {
        return task != null && task.taskType() == taskType()
                && task.aggregateType() == AggregateType.MANAGED_DOCUMENT;
    }

    /** {@inheritDoc} */
    @Override
    public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
        AsyncTask task = context.task();
        requireCurrentArchivedDocument(task);
        try {
            indexPort.deleteSource(SOURCE_TYPE, task.aggregateId());
            if (indexPort.sourceExists(SOURCE_TYPE, task.aggregateId())) {
                throw new KnowledgeIndexException("KNOWLEDGE_DELETE_INCOMPLETE",
                        "Elasticsearch 来源分块删除不完整", true, null);
            }
            return AsyncTaskBusinessMutation.NONE;
        } catch (KnowledgeIndexException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(),
                    exception.getMessage(), exception.retryable());
        }
    }

    /** 确认删除任务仍关联相同版本的已归档托管文档。 */
    private void requireCurrentArchivedDocument(AsyncTask task) {
        boolean current = task.aggregateType() == AggregateType.MANAGED_DOCUMENT
                && documentRepository.findById(task.aggregateId())
                .filter(value -> !value.deleted()
                        && value.status() == ManagedDocumentStatus.ARCHIVED
                        && value.version() == task.aggregateVersion()).isPresent();
        if (!current) {
            throw new AsyncTaskCancelledException(
                    "文档已不存在或不再处于对应归档版本", AsyncTaskBusinessMutation.NONE);
        }
    }
}
