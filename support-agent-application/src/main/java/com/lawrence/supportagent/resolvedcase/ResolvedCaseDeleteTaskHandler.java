package com.lawrence.supportagent.resolvedcase;

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
import com.lawrence.supportagent.resolvedcase.port.ResolvedCaseRepository;

/** 删除已归档案例在 Elasticsearch 中的全部历史分块。 */
public class ResolvedCaseDeleteTaskHandler implements AsyncTaskHandler {
    private static final String SOURCE_TYPE = "RESOLVED_CASE";
    private final ResolvedCaseRepository cases;
    private final KnowledgeIndexPort index;

    /** 注入案例仓储和知识索引端口。 */
    public ResolvedCaseDeleteTaskHandler(ResolvedCaseRepository cases, KnowledgeIndexPort index) {
        this.cases = cases;
        this.index = index;
    }

    /** {@inheritDoc} */
    @Override public AsyncTaskType taskType() { return AsyncTaskType.KNOWLEDGE_DELETE; }

    /** {@inheritDoc} */
    @Override public boolean supports(AsyncTask task) {
        return task != null && task.taskType() == taskType()
                && task.aggregateType() == AggregateType.RESOLVED_CASE;
    }

    /** {@inheritDoc} */
    @Override public AsyncTaskBusinessMutation execute(AsyncTaskExecutionContext context) {
        AsyncTask task = context.task();
        boolean current = cases.findById(task.aggregateId()).filter(value ->
                value.status() == ResolvedCaseStatus.ARCHIVED
                        && value.version() == task.aggregateVersion()).isPresent();
        if (!current) throw new AsyncTaskCancelledException(
                "案例不再处于对应归档版本", AsyncTaskBusinessMutation.NONE);
        try {
            index.deleteSource(SOURCE_TYPE, task.aggregateId());
            if (index.sourceExists(SOURCE_TYPE, task.aggregateId())) {
                throw new KnowledgeIndexException("KNOWLEDGE_DELETE_INCOMPLETE",
                        "案例索引分块删除不完整", true, null);
            }
            return AsyncTaskBusinessMutation.NONE;
        } catch (KnowledgeIndexException exception) {
            throw new AsyncTaskExecutionException(exception.errorCode(), exception.getMessage(),
                    exception.retryable());
        }
    }
}
