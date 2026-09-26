import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatCancelledError, streamChat } from '@/api/chat.stream'
import type { ChatStreamError } from '@/api/chat.stream'
import { writeAccessToken } from '@/api/auth.session'
import type { ApiError } from '@/types/api.types'
import type { ChatEvent, ChatStreamRequest } from '@/types/chat.types'

const request: ChatStreamRequest = {
  conversationId: null,
  clientMessageId: '11111111-1111-1111-1111-111111111111',
  message: '你好',
  expectedConversationVersion: null,
}

afterEach(() => vi.unstubAllGlobals())

describe('streamChat', () => {
  it('处理跨 Chunk 事件并在 answer.completed 后正常结束', async () => {
    writeAccessToken('test-token')
    const events = [event('conversation.started', 1), event('answer.completed', 2)]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(events, [7, 19, 41])))
    const received: ChatEvent[] = []
    await streamChat(request, new AbortController().signal, (item) => received.push(item))
    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.headers).toMatchObject({
      Accept: 'text/event-stream, application/json',
    })
    expect(received.map((item) => item.eventType)).toEqual([
      'conversation.started',
      'answer.completed',
    ])
  })

  it('拒绝重复和乱序 sequence', async () => {
    writeAccessToken('test-token')
    const events = [event('conversation.started', 2), event('answer.completed', 2)]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(events)))
    await expect(
      streamChat(request, new AbortController().signal, () => undefined),
    ).rejects.toMatchObject<Partial<ChatStreamError>>({ code: 'SSE_SEQUENCE_INVALID' })
  })

  it('把用户 Abort 与系统网络错误区分', async () => {
    writeAccessToken('test-token')
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new DOMException('The operation was aborted', 'AbortError')),
    )
    await expect(streamChat(request, controller.signal, () => undefined)).rejects.toBeInstanceOf(
      ChatCancelledError,
    )
  })

  it('保留 403 权限错误的公开 code 和 traceId', async () => {
    writeAccessToken('test-token')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'AUTH_FORBIDDEN',
            message: '无权访问该会话',
            data: null,
            traceId: 'trace-forbidden',
            timestamp: '2026-09-26T00:00:00Z',
          }),
          { status: 403, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )
    await expect(
      streamChat(request, new AbortController().signal, () => undefined),
    ).rejects.toMatchObject<Partial<ApiError>>({
      status: 403,
      code: 'AUTH_FORBIDDEN',
      traceId: 'trace-forbidden',
    })
  })
})

/** 创建字段齐全的测试聊天事件。 */
function event(eventType: string, sequence: number): ChatEvent {
  return {
    eventId: `00000000-0000-0000-0000-00000000000${sequence}`,
    eventType,
    runId: '22222222-2222-2222-2222-222222222222',
    conversationId: '33333333-3333-3333-3333-333333333333',
    sequence,
    timestamp: '2026-09-26T00:00:00Z',
    data:
      eventType === 'answer.completed'
        ? { answer: '完成', citations: [], conversationVersion: 1, resultStatus: 'GREETING' }
        : { conversationVersion: 0, replayed: false },
  }
}

/** 按指定字符边界切分 SSE 字节流，以验证网络 Chunk 不等于事件。 */
function sseResponse(events: ChatEvent[], cuts: number[] = []): Response {
  const text = events
    .map(
      (item) => `id: ${item.eventId}\nevent: ${item.eventType}\ndata: ${JSON.stringify(item)}\n\n`,
    )
    .join('')
  const chunks: Uint8Array[] = []
  let previous = 0
  for (const cut of cuts) {
    chunks.push(new TextEncoder().encode(text.slice(previous, cut)))
    previous = cut
  }
  chunks.push(new TextEncoder().encode(text.slice(previous)))
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk))
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}
