import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteFeedback, getFeedback, putFeedback } from '@/api/feedback.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult } from '@/types/api.types'
import type { AnswerFeedback } from '@/types/feedback.types'

const runId = '22222222-2222-2222-2222-222222222222'
const feedback: AnswerFeedback = {
  runId,
  rating: 'NOT_HELPFUL',
  reason: 'INCOMPLETE',
  note: '缺少恢复步骤',
  version: 1,
  createdAt: '2026-10-12T00:00:00Z',
  updatedAt: '2026-10-12T01:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('feedback api', () => {
  it('按运行 ID 读取本人反馈', async () => {
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: success(feedback) })
    await expect(getFeedback(runId)).resolves.toEqual(feedback)
    expect(get).toHaveBeenCalledWith(`/answer-feedback/${runId}`)
  })

  it('首次创建和更正使用同一接口并显式传递期望版本', async () => {
    const put = vi.spyOn(httpClient, 'put').mockResolvedValue({ data: success(feedback) })
    await putFeedback(runId, {
      rating: 'NOT_HELPFUL',
      reason: 'INCOMPLETE',
      note: null,
      expectedVersion: null,
    })
    await putFeedback(runId, {
      rating: 'HELPFUL',
      reason: null,
      note: null,
      expectedVersion: 1,
    })
    expect(put).toHaveBeenNthCalledWith(1, `/answer-feedback/${runId}`, {
      rating: 'NOT_HELPFUL',
      reason: 'INCOMPLETE',
      note: null,
      expectedVersion: null,
    })
    expect(put).toHaveBeenNthCalledWith(2, `/answer-feedback/${runId}`, {
      rating: 'HELPFUL',
      reason: null,
      note: null,
      expectedVersion: 1,
    })
  })

  it('删除使用当前版本防止误删并发更正', async () => {
    const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ data: success(null) })
    await deleteFeedback(runId, 1)
    expect(remove).toHaveBeenCalledWith(`/answer-feedback/${runId}`, {
      params: { expectedVersion: 1 },
    })
  })
})

/** 包装统一 API 成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-feedback',
    timestamp: '2026-10-12T00:00:00Z',
  }
}
