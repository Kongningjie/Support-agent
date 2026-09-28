/** 持久化异步任务类型。 */
export type AsyncTaskType = 'CASE_GENERATION' | 'KNOWLEDGE_INDEX' | 'KNOWLEDGE_DELETE'

/** 持久化异步任务状态。 */
export type AsyncTaskStatus =
  'PENDING' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'DEAD' | 'CANCELLED'

/** 异步任务关联的业务聚合类型。 */
export type AggregateType = 'TICKET' | 'MANAGED_DOCUMENT' | 'RESOLVED_CASE'

/** 不含 Worker 锁和租约的异步任务安全视图。 */
export interface AsyncTaskView {
  taskId: string
  taskType: AsyncTaskType
  aggregateType: AggregateType
  aggregateId: string
  aggregateVersion: number
  status: AsyncTaskStatus
  attemptCount: number
  maxAttempts: number
  nextRunAt: string
  lastErrorCode: string | null
  lastErrorMessage: string | null
  retryOfTaskId: string | null
  manualRetryReason: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
}
