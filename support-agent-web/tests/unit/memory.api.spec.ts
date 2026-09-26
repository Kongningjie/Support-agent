import { afterEach, describe, expect, it, vi } from 'vitest'
import * as memoryApi from '@/api/memory.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult } from '@/types/api.types'
import type { UserMemory } from '@/types/memory.types'

afterEach(() => vi.restoreAllMocks())

describe('memory api', () => {
  it('确认候选时同时发送当前版本和幂等键', async () => {
    const memory = sampleMemory()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: success({ ...memory, status: 'ACTIVE', version: 1 }),
    })
    await memoryApi.confirmMemory(memory, 'same-action-key')
    expect(post).toHaveBeenCalledWith(
      `/memories/${memory.memoryId}/confirm`,
      { expectedVersion: 0 },
      { headers: { 'Idempotency-Key': 'same-action-key' } },
    )
  })

  it('更正时明确区分保持、设置与清除失效时间', async () => {
    const memory = sampleMemory()
    const patch = vi.spyOn(httpClient, 'patch').mockResolvedValue({ data: success(memory) })
    await memoryApi.reviseMemory(
      memory.memoryId,
      { content: '更新正文', clearExpiresAt: true, pinned: true, expectedVersion: 0 },
      'revise-key',
    )
    expect(patch).toHaveBeenCalledWith(
      `/memories/${memory.memoryId}`,
      { content: '更新正文', clearExpiresAt: true, pinned: true, expectedVersion: 0 },
      { headers: { 'Idempotency-Key': 'revise-key' } },
    )
  })
})

/** 创建长期记忆契约测试数据。 */
function sampleMemory(): UserMemory {
  return {
    memoryId: '11111111-1111-1111-1111-111111111111',
    memoryType: 'ENVIRONMENT',
    content: '开发环境使用 Windows 11',
    status: 'PROPOSED',
    pinned: false,
    sourceConversationId: '22222222-2222-2222-2222-222222222222',
    sourceTurnId: '33333333-3333-3333-3333-333333333333',
    expiresAt: null,
    version: 0,
    createdAt: '2026-09-26T00:00:00Z',
    updatedAt: '2026-09-26T00:00:00Z',
  }
}

/** 包装后端统一成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-test',
    timestamp: '2026-09-26T00:00:00Z',
  }
}
