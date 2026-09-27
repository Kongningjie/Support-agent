import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetConversation } from '@/api/conversation.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult } from '@/types/api.types'
import type { ConversationOverview } from '@/types/chat.types'

afterEach(() => vi.restoreAllMocks())

describe('conversation api', () => {
  it('重置会话时显式发送目标空间和当前版本', async () => {
    const overview = sampleOverview()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(overview) })

    await expect(resetConversation(overview.conversationId, 3, overview.spaceId)).resolves.toEqual(
      overview,
    )
    expect(post).toHaveBeenCalledWith(`/conversations/${overview.conversationId}/reset`, {
      spaceId: overview.spaceId,
      expectedVersion: 3,
    })
  })
})

/** 创建会话重置契约测试使用的空间化响应。 */
function sampleOverview(): ConversationOverview {
  return {
    conversationId: '33333333-3333-3333-3333-333333333333',
    spaceId: '11111111-1111-1111-1111-111111111111',
    status: 'IDLE',
    version: 4,
    generation: 1,
    summaryVersion: 0,
    lastAccessAt: '2026-09-27T00:00:00Z',
    expiresAt: '2026-10-04T00:00:00Z',
  }
}

/** 包装后端统一成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-conversation-test',
    timestamp: '2026-09-27T00:00:00Z',
  }
}
