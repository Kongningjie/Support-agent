import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as conversationApi from '@/api/conversation.api'
import { useChatStore } from '@/stores/chat.store'
import { writeAccessToken } from '@/api/auth.session'
import { ApiError } from '@/types/api.types'
import type { ChatEvent } from '@/types/chat.types'

beforeEach(() => {
  setActivePinia(createPinia())
  writeAccessToken('test-token')
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('chat store', () => {
  it('完成事件以服务端 conversationVersion 更新当前会话', async () => {
    const events = [
      event('conversation.started', 1, { conversationVersion: 0, replayed: false }),
      event('answer.started', 2, { contentType: 'text/markdown' }),
      event('answer.delta', 3, { text: '安全回答' }),
      event('answer.completed', 4, {
        answer: '安全回答',
        citations: [],
        conversationVersion: 1,
        resultStatus: 'GREETING',
      }),
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(events)))
    const store = useChatStore()
    await store.send('你好')
    expect(store.conversation?.version).toBe(1)
    expect(store.conversation?.status).toBe('IDLE')
    expect(store.messages[0]?.answer).toBe('安全回答')
    expect(store.messages[0]?.state).toBe('completed')
  })

  it('失败重试复用原 clientMessageId', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 1, { conversationVersion: 0, replayed: false }),
          event('error', 2, {
            code: 'RETRIEVAL_FAILED',
            message: '知识检索暂时不可用',
            retryable: true,
          }),
        ]),
      )
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 1, { conversationVersion: 0, replayed: true }),
          event('answer.completed', 2, {
            answer: '重放完成',
            citations: [],
            conversationVersion: 1,
            resultStatus: 'GREETING',
          }),
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)
    const store = useChatStore()
    await store.send('你好')
    const firstId = store.messages[0]?.clientMessageId
    expect(store.messages[0]?.retryable).toBe(true)
    expect(store.conversation?.status).toBe('IDLE')
    await store.retry(store.messages[0]?.key || '')
    const bodies = fetchMock.mock.calls.map(
      (call) => JSON.parse(String(call[1]?.body)) as Record<string, unknown>,
    )
    expect(bodies[0]?.clientMessageId).toBe(firstId)
    expect(bodies[1]?.clientMessageId).toBe(firstId)
  })

  it('重置发生 409 时刷新服务端最新会话版本', async () => {
    const overview = {
      conversationId: '33333333-3333-3333-3333-333333333333',
      status: 'IDLE' as const,
      version: 2,
      generation: 0,
      summaryVersion: 0,
      lastAccessAt: '2026-09-26T00:00:00Z',
      expiresAt: '2026-10-03T00:00:00Z',
    }
    vi.spyOn(conversationApi, 'getConversation')
      .mockResolvedValueOnce({ conversation: overview, recentTurns: [] })
      .mockResolvedValueOnce({ conversation: { ...overview, version: 3 }, recentTurns: [] })
    vi.spyOn(conversationApi, 'resetConversation').mockRejectedValue(
      new ApiError('会话版本已变化', 409, 'CONVERSATION_VERSION_CONFLICT', 'trace-conflict', false),
    )
    const store = useChatStore()
    await store.openConversation(overview.conversationId)
    await expect(store.reset()).rejects.toBeInstanceOf(ApiError)
    expect(store.conversation?.version).toBe(3)
  })
})

/** 创建 Store 测试使用的聊天事件。 */
function event(eventType: string, sequence: number, data: Record<string, unknown>): ChatEvent {
  return {
    eventId: `00000000-0000-0000-0000-00000000000${sequence}`,
    eventType,
    runId: '22222222-2222-2222-2222-222222222222',
    conversationId: '33333333-3333-3333-3333-333333333333',
    sequence,
    timestamp: '2026-09-26T00:00:00Z',
    data,
  }
}

/** 把事件数组包装为浏览器可读的 SSE Response。 */
function sseResponse(events: ChatEvent[]): Response {
  const text = events
    .map(
      (item) => `id: ${item.eventId}\nevent: ${item.eventType}\ndata: ${JSON.stringify(item)}\n\n`,
    )
    .join('')
  return new Response(text, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}
