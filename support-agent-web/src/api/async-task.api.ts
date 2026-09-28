import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  AggregateType,
  AsyncTaskStatus,
  AsyncTaskType,
  AsyncTaskView,
} from '@/types/async-task.types'

/** 按任务、状态和聚合条件分页读取异步任务。 */
export async function listTasks(filters: {
  taskType: AsyncTaskType | null
  status: AsyncTaskStatus | null
  aggregateType: AggregateType | null
  aggregateId: string
  page: number
  size: number
}): Promise<PageResult<AsyncTaskView>> {
  const response = await httpClient.get<ApiResult<PageResult<AsyncTaskView>>>('/async-tasks', {
    params: filters,
  })
  return requireData(response.data.data)
}

/** 读取不包含 Worker 锁信息的任务详情。 */
export async function getTask(taskId: string): Promise<AsyncTaskView> {
  const response = await httpClient.get<ApiResult<AsyncTaskView>>(`/async-tasks/${taskId}`)
  return requireData(response.data.data)
}

/** 为仍适用于当前业务状态的 DEAD 任务创建人工重试任务。 */
export async function retryTask(
  taskId: string,
  reason: string,
  idempotencyKey: string,
): Promise<AsyncTaskView> {
  const response = await httpClient.post<ApiResult<AsyncTaskView>>(`/async-tasks/${taskId}/retry`, {
    reason,
    idempotencyKey,
  })
  return requireData(response.data.data)
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('异步任务接口未返回预期数据')
  return value
}
