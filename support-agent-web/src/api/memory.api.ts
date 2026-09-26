import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  MemorySettings,
  MemoryStatus,
  ReviseMemoryRequest,
  UserMemory,
} from '@/types/memory.types'

/** 读取当前用户的长期记忆开关。 */
export async function getMemorySettings(): Promise<MemorySettings> {
  const response = await httpClient.get<ApiResult<MemorySettings>>('/users/me/memory-settings')
  return requireData(response.data.data)
}

/** 使用幂等键和乐观锁修改长期记忆开关。 */
export async function updateMemorySettings(
  enabled: boolean,
  expectedVersion: number,
  idempotencyKey: string,
): Promise<MemorySettings> {
  const response = await httpClient.patch<ApiResult<MemorySettings>>(
    '/users/me/memory-settings',
    { enabled, expectedVersion },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 分页读取本人记忆，可按生命周期状态筛选。 */
export async function listMemories(
  status: MemoryStatus | null,
  page = 1,
  size = 20,
): Promise<PageResult<UserMemory>> {
  const response = await httpClient.get<ApiResult<PageResult<UserMemory>>>('/memories', {
    params: { status: status || undefined, page, size },
  })
  return requireData(response.data.data)
}

/** 确认候选长期记忆。 */
export async function confirmMemory(
  memory: UserMemory,
  idempotencyKey: string,
): Promise<UserMemory> {
  const response = await httpClient.post<ApiResult<UserMemory>>(
    `/memories/${memory.memoryId}/confirm`,
    { expectedVersion: memory.version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 更正记忆正文、失效时间或固定标志。 */
export async function reviseMemory(
  memoryId: string,
  request: ReviseMemoryRequest,
  idempotencyKey: string,
): Promise<UserMemory> {
  const response = await httpClient.patch<ApiResult<UserMemory>>(`/memories/${memoryId}`, request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return requireData(response.data.data)
}

/** 撤销记忆的上下文注入资格。 */
export async function revokeMemory(
  memory: UserMemory,
  idempotencyKey: string,
): Promise<UserMemory> {
  const response = await httpClient.post<ApiResult<UserMemory>>(
    `/memories/${memory.memoryId}/revoke`,
    { expectedVersion: memory.version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 永久删除记忆及其正文。 */
export async function deleteMemory(memory: UserMemory, idempotencyKey: string): Promise<void> {
  await httpClient.delete<ApiResult<null>>(`/memories/${memory.memoryId}`, {
    params: { expectedVersion: memory.version },
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

/** 拒绝后端成功响应缺少必须数据的契约异常。 */
function requireData<T>(data: T | null): T {
  if (data === null) {
    throw new Error('长期记忆接口未返回预期数据')
  }
  return data
}
