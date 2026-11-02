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
  it('较早轮次重放不能降低服务端当前会话版本', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        sseResponse([
          event('conversation.started', 20, { conversationVersion: 3, replayed: true }),
          event('answer.completed', 21, {
            answer: '原成功结果',
            citations: [],
            conversationVersion: 1,
            resultStatus: 'GREETING',
          }),
        ]),
      ),
    )
    const store = useChatStore()
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    expect(store.conversation?.version).toBe(3)
    expect(store.messages[0]?.answer).toBe('原成功结果')
  })
  it('刷新发现代际变化时保留原消息标识但禁止跨代际重试', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          sseResponse([
            event('conversation.started', 1, { conversationVersion: 0, replayed: false }),
            event('answer.delta', 2, { text: '旧临时正文' }),
          ]),
        ),
    )
    const store = useChatStore()
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    const current = store.conversation
    if (!current) throw new Error('缺少测试会话')
    const key = store.messages[0]?.key
    vi.spyOn(conversationApi, 'getConversation').mockResolvedValue({
      conversation: { ...current, generation: 1, status: 'IDLE' },
      recentTurns: [],
    })
    await store.openConversation(current.conversationId)
    expect(store.messages[0]).toMatchObject({ key, answer: '', retryable: false, state: 'failed' })
    expect(store.messages[0]?.errorMessage).toContain('旧代际消息不能重试')
    await store.retry(key || '')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('用户取消后显示结果未知且允许原 ID 显式重试，不自动重发', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(
        (_url, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            )
          }),
      )
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 1, { conversationVersion: 0, replayed: true }),
          event('answer.completed', 2, {
            answer: '已确认结果',
            citations: [],
            conversationVersion: 1,
            resultStatus: 'GREETING',
          }),
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)
    const store = useChatStore()
    const pending = store.send('你好', '00000000-0000-0000-0000-000000000001')
    store.cancel()
    await pending
    const item = store.messages[0]
    expect(item?.state).toBe('cancelled')
    expect(item?.errorMessage).toContain('结果状态待确认')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await store.retry(item?.key || '')
    const bodies = fetchMock.mock.calls.map(
      (call) => JSON.parse(String(call[1]?.body)) as Record<string, unknown>,
    )
    expect(bodies[1]?.clientMessageId).toBe(bodies[0]?.clientMessageId)
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]?.state).toBe('completed')
  })

  it('重放替换临时正文、引用和建议，忙响应不丢失原消息或自动重试', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 1, { conversationVersion: 0, replayed: false }),
          event('answer.delta', 2, { text: '临时正文' }),
          event('ticket.suggested', 3, { suggestionId: 'old', expiresAt: '2099-01-01T00:00:00Z' }),
        ]),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CHAT_CONVERSATION_BUSY', message: '会话繁忙' }), {
          status: 409,
        }),
      )
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 10, { conversationVersion: 1, replayed: true }),
          event('answer.delta', 11, { text: '安全结果' }),
          event('answer.completed', 12, {
            answer: '安全结果',
            citations: [],
            conversationVersion: 1,
            resultStatus: 'GREETING',
          }),
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)
    const refresh = vi.spyOn(conversationApi, 'getConversation')
    const store = useChatStore()
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    const key = store.messages[0]?.key || ''
    await store.retry(key)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(refresh).not.toHaveBeenCalled()
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]).toMatchObject({
      key,
      answer: '',
      suggestionId: null,
      retryable: true,
    })
    await store.retry(key)
    expect(store.messages[0]).toMatchObject({
      answer: '安全结果',
      citations: [],
      suggestionId: null,
      state: 'completed',
      retryable: false,
    })
    const ids = fetchMock.mock.calls.map(
      (call) => (JSON.parse(String(call[1]?.body)) as Record<string, unknown>).clientMessageId,
    )
    expect(new Set(ids).size).toBe(1)
  })

  it('版本冲突刷新保留待确认消息；已保存 runId 按服务端事实合并', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        sseResponse([
          event('conversation.started', 1, { conversationVersion: 0, replayed: false }),
        ]),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'CHAT_VERSION_CONFLICT', message: '版本变化' }), {
          status: 409,
        }),
      )
    vi.stubGlobal('fetch', fetchMock)
    const store = useChatStore()
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    const current = store.conversation
    if (!current) throw new Error('缺少测试会话')
    const overview = { ...current, version: 1, status: 'IDLE' as const }
    vi.spyOn(conversationApi, 'getConversation')
      .mockResolvedValueOnce({ conversation: overview, recentTurns: [] })
      .mockResolvedValueOnce({
        conversation: overview,
        recentTurns: [
          {
            turnId: 'saved',
            runId: store.messages[0]?.runId || '',
            userMessage: '你好',
            answer: '服务端结果',
            intent: 'GREETING',
            retrievalStatus: null,
            citations: [],
            completedAt: '2026-11-02T00:00:00Z',
            conversationVersion: 1,
          },
        ],
      })
    const key = store.messages[0]?.key || ''
    await store.retry(key)
    expect(store.messages[0]?.key).toBe(key)
    expect(store.messages[0]?.retryable).toBe(true)
    await store.openConversation(overview.conversationId)
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]).toMatchObject({ answer: '服务端结果', state: 'completed' })
  })
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
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    expect(store.conversation?.version).toBe(1)
    expect(store.conversation?.status).toBe('IDLE')
    expect(store.messages[0]?.answer).toBe('安全回答')
    expect(store.messages[0]?.state).toBe('completed')
    expect(store.messages[0]?.runId).toBe('22222222-2222-2222-2222-222222222222')
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
    await store.send('你好', '00000000-0000-0000-0000-000000000001')
    const firstId = store.messages[0]?.clientMessageId
    expect(store.messages[0]?.retryable).toBe(true)
    // 传输错误不能证明服务端已经释放占用，保留最后一次服务端状态。
    expect(store.conversation?.status).toBe('RUNNING')
    await store.retry(store.messages[0]?.key || '')
    const bodies = fetchMock.mock.calls.map(
      (call) => JSON.parse(String(call[1]?.body)) as Record<string, unknown>,
    )
    expect(bodies[0]?.clientMessageId).toBe(firstId)
    expect(bodies[1]?.clientMessageId).toBe(firstId)
    expect(bodies[0]?.spaceId).toBe('00000000-0000-0000-0000-000000000001')
    expect(bodies[1]?.spaceId).toBe('00000000-0000-0000-0000-000000000001')
  })

  it('重置发生 409 时刷新服务端最新会话版本', async () => {
    const overview = {
      conversationId: '33333333-3333-3333-3333-333333333333',
      spaceId: '00000000-0000-0000-0000-000000000001',
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
    await expect(store.reset('44444444-4444-4444-4444-444444444444')).rejects.toBeInstanceOf(
      ApiError,
    )
    expect(store.conversation?.version).toBe(3)
    expect(conversationApi.resetConversation).toHaveBeenCalledWith(
      overview.conversationId,
      overview.version,
      '44444444-4444-4444-4444-444444444444',
    )
  })

  it('历史成功轮次保留可用于本人评价的原始 runId', async () => {
    vi.spyOn(conversationApi, 'getConversation').mockResolvedValue({
      conversation: {
        conversationId: '33333333-3333-3333-3333-333333333333',
        spaceId: '00000000-0000-0000-0000-000000000001',
        status: 'IDLE',
        version: 1,
        generation: 0,
        summaryVersion: 0,
        lastAccessAt: '2026-09-26T00:00:00Z',
        expiresAt: '2026-10-03T00:00:00Z',
      },
      recentTurns: [
        {
          turnId: '11111111-1111-1111-1111-111111111111',
          runId: '22222222-2222-2222-2222-222222222222',
          userMessage: '你好',
          answer: '您好',
          intent: 'GREETING',
          retrievalStatus: null,
          citations: [],
          completedAt: '2026-09-26T00:00:00Z',
          conversationVersion: 1,
        },
      ],
    })
    const store = useChatStore()
    await store.openConversation('33333333-3333-3333-3333-333333333333')
    expect(store.messages[0]?.runId).toBe('22222222-2222-2222-2222-222222222222')
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
