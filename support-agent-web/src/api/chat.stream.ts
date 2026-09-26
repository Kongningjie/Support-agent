import { clearAccessToken, readAccessToken } from './auth.session'
import { AUTH_UNAUTHORIZED_EVENT } from './http.client'
import { SseParser, type ParsedSseFrame } from './sse.parser'
import type { ApiResult } from '@/types/api.types'
import { ApiError } from '@/types/api.types'
import type { ChatEvent, ChatStreamRequest } from '@/types/chat.types'

/** 浏览器主动取消聊天时使用的可识别异常。 */
export class ChatCancelledError extends Error {
  /** 创建不应弹出系统错误的取消异常。 */
  constructor() {
    super('聊天已取消')
    this.name = 'ChatCancelledError'
  }
}

/** SSE 正常建立后因断流或协议错误产生的安全异常。 */
export class ChatStreamError extends Error {
  /**
   * @param message 可向用户展示的流错误
   * @param code 稳定前端或服务端错误码
   * @param retryable 是否允许用户复用原消息幂等重试
   */
  constructor(
    message: string,
    readonly code: string,
    readonly retryable: boolean,
  ) {
    super(message)
    this.name = 'ChatStreamError'
  }
}

/**
 * 使用 POST、Bearer Token 和原生 ReadableStream 消费聊天 SSE。
 *
 * @param request 首次或后续聊天请求
 * @param signal 页面或用户取消信号
 * @param onEvent 已通过结构与序号校验的事件回调
 */
export async function streamChat(
  request: ChatStreamRequest,
  signal: AbortSignal,
  onEvent: (event: ChatEvent) => void,
): Promise<void> {
  const token = readAccessToken()
  if (!token) {
    throw new ApiError('登录状态已失效，请重新登录', 401, 'AUTH_UNAUTHORIZED', null, false)
  }
  let response: Response
  try {
    response = await fetch(`${apiBaseUrl()}/chat/stream`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream, application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(request),
      signal,
    })
  } catch (error) {
    if (signal.aborted || isAbortError(error)) throw new ChatCancelledError()
    throw new ChatStreamError('无法连接聊天服务，请确认后端已启动', 'NETWORK_ERROR', true)
  }
  if (!response.ok) {
    throw await responseError(response)
  }
  if (!response.body) {
    throw new ChatStreamError('聊天服务未返回事件流', 'SSE_BODY_MISSING', true)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  let lastSequence: number | null = null
  let terminal = false
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const frames = parser.push(decoder.decode(value, { stream: true }))
      ;({ lastSequence, terminal } = consumeFrames(frames, lastSequence, terminal, onEvent))
    }
    const frames = parser.push(decoder.decode())
    ;({ lastSequence, terminal } = consumeFrames(frames, lastSequence, terminal, onEvent))
    ;({ lastSequence, terminal } = consumeFrames(parser.finish(), lastSequence, terminal, onEvent))
  } catch (error) {
    if (signal.aborted || isAbortError(error)) throw new ChatCancelledError()
    if (error instanceof ChatStreamError) throw error
    throw new ChatStreamError('聊天连接意外中断，可重试同一条消息', 'SSE_INTERRUPTED', true)
  } finally {
    reader.releaseLock()
  }
  if (!terminal) {
    throw new ChatStreamError('聊天连接在完成前中断，可重试同一条消息', 'SSE_INCOMPLETE', true)
  }
}

/** 校验一批帧并返回新的序号和终止状态。 */
function consumeFrames(
  frames: ParsedSseFrame[],
  previousSequence: number | null,
  previousTerminal: boolean,
  onEvent: (event: ChatEvent) => void,
): { lastSequence: number | null; terminal: boolean } {
  let lastSequence = previousSequence
  let terminal = previousTerminal
  for (const frame of frames) {
    const event = parseChatEvent(frame)
    if (terminal) {
      throw new ChatStreamError(
        '聊天服务在终止事件后仍发送业务数据',
        'SSE_EVENT_AFTER_TERMINAL',
        false,
      )
    }
    if (lastSequence !== null && event.sequence <= lastSequence) {
      throw new ChatStreamError('聊天事件出现重复或乱序，请刷新会话', 'SSE_SEQUENCE_INVALID', true)
    }
    lastSequence = event.sequence
    terminal = event.eventType === 'answer.completed' || event.eventType === 'error'
    onEvent(event)
  }
  return { lastSequence, terminal }
}

/** 把 JSON 数据帧收敛为最小可信事件结构。 */
function parseChatEvent(frame: ParsedSseFrame): ChatEvent {
  let value: unknown
  try {
    value = JSON.parse(frame.data)
  } catch {
    throw new ChatStreamError('聊天服务返回了无法解析的事件', 'SSE_INVALID_JSON', true)
  }
  if (!value || typeof value !== 'object') {
    throw new ChatStreamError('聊天服务返回了无效事件', 'SSE_INVALID_EVENT', true)
  }
  const candidate = value as Record<string, unknown>
  if (
    typeof candidate.eventId !== 'string' ||
    typeof candidate.eventType !== 'string' ||
    typeof candidate.runId !== 'string' ||
    typeof candidate.conversationId !== 'string' ||
    typeof candidate.sequence !== 'number' ||
    typeof candidate.timestamp !== 'string' ||
    !candidate.data ||
    typeof candidate.data !== 'object'
  ) {
    throw new ChatStreamError('聊天事件缺少必须字段', 'SSE_INVALID_EVENT', true)
  }
  if (
    (frame.event && frame.event !== candidate.eventType) ||
    (frame.id && frame.id !== candidate.eventId)
  ) {
    throw new ChatStreamError('聊天事件外层与正文不一致', 'SSE_EVENT_MISMATCH', false)
  }
  return candidate as unknown as ChatEvent
}

/** 将非 2xx 响应转换为不泄露 Header 的公开错误。 */
async function responseError(response: Response): Promise<ApiError> {
  let body: ApiResult<unknown> | null = null
  try {
    body = (await response.json()) as ApiResult<unknown>
  } catch {
    // 非 JSON 错误使用 HTTP 状态默认文案。
  }
  if (response.status === 401) {
    clearAccessToken()
    window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
  }
  const messages: Record<number, string> = {
    400: '聊天请求参数不符合要求',
    401: '登录状态已失效，请重新登录',
    403: '当前账号无权访问该会话',
    404: '会话不存在或已过期',
    409: '会话版本已变化，请刷新后继续',
    410: '会话已经过期，请发起新会话',
    422: '消息未通过安全校验',
    429: '请求过于频繁，请稍后重试',
    503: '聊天依赖暂不可用，请稍后重试',
  }
  return new ApiError(
    body?.message || messages[response.status] || '聊天请求失败，请稍后重试',
    response.status,
    body?.code || `HTTP_${response.status}`,
    body?.traceId || null,
    response.status === 429 || response.status >= 500,
  )
}

/** 返回 Vite 注入或默认的 API 基础路径，并移除末尾斜杠。 */
function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
}

/** 在不依赖浏览器具体 DOMException 实现的前提下识别取消。 */
function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}
