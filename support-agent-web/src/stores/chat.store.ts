import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ChatCancelledError, ChatStreamError, streamChat } from '@/api/chat.stream'
import * as conversationApi from '@/api/conversation.api'
import type {
  ChatCitation,
  ChatEvent,
  ChatMessage,
  ConversationDetails,
  ConversationOverview,
  RetrievalStatus,
} from '@/types/chat.types'
import { ApiError } from '@/types/api.types'

/** Chat 页面跨路由共享的当前会话、消息和流生命周期。 */
export const useChatStore = defineStore('chat', () => {
  const conversation = ref<ConversationOverview | null>(null)
  const messages = ref<ChatMessage[]>([])
  const progress = ref<string | null>(null)
  const loading = ref(false)
  const controller = ref<AbortController | null>(null)

  const streaming = computed(() => controller.value !== null)
  const canSend = computed(() => !loading.value && !streaming.value)

  /** 清空当前页面状态，下一条消息将创建新会话。 */
  function startNewConversation(): void {
    cancel()
    conversation.value = null
    messages.value = []
    progress.value = null
  }

  /** 从 REST 详情恢复最近成功轮次并继续现有会话。 */
  async function openConversation(conversationId: string): Promise<void> {
    cancel()
    loading.value = true
    try {
      const details = await conversationApi.getConversation(conversationId)
      applyDetails(details)
    } finally {
      loading.value = false
    }
  }

  /**
   * 发送新消息；创建 clientMessageId 后交给内部方法，失败重试可复用该 ID。
   *
   * @param rawMessage 用户输入，去除首尾空白后必须为 1～4000 字符
   */
  async function send(rawMessage: string): Promise<void> {
    const message = rawMessage.trim()
    if (!message || message.length > 4000 || !canSend.value) return
    const item = createRunningMessage(message, crypto.randomUUID())
    messages.value.push(item)
    await execute(item)
  }

  /** 重试失败消息并复用原 clientMessageId，避免创建第二个业务动作。 */
  async function retry(key: string): Promise<void> {
    const item = messages.value.find((candidate) => candidate.key === key)
    if (
      !item ||
      !item.clientMessageId ||
      item.state !== 'failed' ||
      !item.retryable ||
      !canSend.value
    )
      return
    item.answer = ''
    item.citations = []
    item.errorMessage = null
    item.state = 'running'
    await execute(item)
  }

  /** 主动取消当前流；已收到的片段保留但明确标记为未完成。 */
  function cancel(): void {
    controller.value?.abort()
  }

  /** 使用最新版本重置会话；冲突时刷新服务端详情。 */
  async function reset(): Promise<void> {
    if (!conversation.value || streaming.value) return
    loading.value = true
    try {
      conversation.value = await conversationApi.resetConversation(
        conversation.value.conversationId,
        conversation.value.version,
      )
      messages.value = []
      progress.value = null
    } catch (error) {
      await refreshAfterConflict(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  /** 使用最新版本永久删除当前会话，并回到新会话状态。 */
  async function remove(): Promise<void> {
    if (!conversation.value || streaming.value) return
    loading.value = true
    try {
      await conversationApi.deleteConversation(
        conversation.value.conversationId,
        conversation.value.version,
      )
      startNewConversation()
    } catch (error) {
      await refreshAfterConflict(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  /** 执行一次新消息或幂等重试，并把业务事件投影到当前消息。 */
  async function execute(item: ChatMessage): Promise<void> {
    const activeController = new AbortController()
    controller.value = activeController
    progress.value = '正在建立安全会话'
    try {
      await streamChat(
        {
          conversationId: conversation.value?.conversationId || null,
          clientMessageId: item.clientMessageId as string,
          message: item.userMessage,
          expectedConversationVersion: conversation.value?.version ?? null,
        },
        activeController.signal,
        (event) => applyEvent(item, event),
      )
    } catch (error) {
      if (error instanceof ChatCancelledError) {
        item.state = 'cancelled'
        item.errorMessage = '本轮已取消，未完成内容不会作为成功回答保存。'
        item.retryable = true
      } else {
        item.state = 'failed'
        item.errorMessage = safeErrorMessage(error)
        item.retryable =
          error instanceof ChatStreamError
            ? error.retryable
            : error instanceof ApiError && error.retryable
        if (error instanceof ApiError && (error.status === 404 || error.status === 410)) {
          conversation.value = null
        }
        if (error instanceof ApiError && error.status === 409 && conversation.value) {
          await openConversation(conversation.value.conversationId)
        }
      }
      if (conversation.value?.status === 'RUNNING') {
        conversation.value = { ...conversation.value, status: 'IDLE' }
      }
    } finally {
      if (controller.value === activeController) controller.value = null
      progress.value = null
    }
  }

  /** 按稳定事件类型更新运行进度、回答、引用、建议与会话版本。 */
  function applyEvent(item: ChatMessage, event: ChatEvent): void {
    if (event.eventType === 'conversation.started') {
      const version = numberValue(event.data.conversationVersion)
      conversation.value = {
        conversationId: event.conversationId,
        status: 'RUNNING',
        version,
        generation: conversation.value?.generation ?? 0,
        summaryVersion: conversation.value?.summaryVersion ?? 0,
        lastAccessAt: event.timestamp,
        expiresAt: conversation.value?.expiresAt ?? event.timestamp,
      }
      progress.value = booleanValue(event.data.replayed) ? '正在恢复已完成结果' : '正在识别问题意图'
    } else if (event.eventType === 'retrieval.started') {
      progress.value = '正在检索企业知识'
    } else if (event.eventType === 'retrieval.completed') {
      item.retrievalStatus = retrievalStatusValue(event.data.status)
      progress.value =
        item.retrievalStatus === 'GROUNDED' ? '已找到可靠知识，正在生成回答' : '检索已完成'
    } else if (event.eventType === 'answer.started') {
      progress.value = '回答已通过校验，正在安全传输'
    } else if (event.eventType === 'answer.delta') {
      item.answer += stringValue(event.data.text)
    } else if (event.eventType === 'citation') {
      const citation = citationValue(event.data)
      if (!item.citations.some((candidate) => candidate.citationId === citation.citationId)) {
        item.citations.push(citation)
      }
    } else if (event.eventType === 'ticket.suggested') {
      item.suggestionId = stringValue(event.data.suggestionId)
      item.suggestionExpiresAt = stringValue(event.data.expiresAt)
    } else if (event.eventType === 'answer.completed') {
      item.answer = stringValue(event.data.answer)
      item.citations = arrayCitationValue(event.data.citations)
      item.resultStatus = stringValue(event.data.resultStatus)
      item.state = 'completed'
      item.completedAt = event.timestamp
      item.errorMessage = null
      if (conversation.value) {
        conversation.value = {
          ...conversation.value,
          status: 'IDLE',
          version: numberValue(event.data.conversationVersion),
          lastAccessAt: event.timestamp,
        }
      }
    } else if (event.eventType === 'error') {
      item.state = 'failed'
      item.errorMessage = stringValue(event.data.message) || '聊天处理失败，请稍后重试'
      item.retryable = booleanValue(event.data.retryable)
      throw new ChatStreamError(item.errorMessage, stringValue(event.data.code), item.retryable)
    }
  }

  /** 将详情轮次替换为只读成功消息，避免保留上一会话临时状态。 */
  function applyDetails(details: ConversationDetails): void {
    conversation.value = details.conversation
    messages.value = details.recentTurns.map((turn) => ({
      key: turn.turnId,
      clientMessageId: null,
      userMessage: turn.userMessage,
      answer: turn.answer,
      citations: turn.citations,
      retrievalStatus: turn.retrievalStatus,
      resultStatus: null,
      suggestionId: null,
      suggestionExpiresAt: null,
      completedAt: turn.completedAt,
      state: 'completed',
      errorMessage: null,
      retryable: false,
    }))
    progress.value = null
  }

  /** 409 后刷新最新详情；其他错误保持用户当前输入。 */
  async function refreshAfterConflict(error: unknown): Promise<void> {
    if (error instanceof ApiError && error.status === 409 && conversation.value) {
      const details = await conversationApi.getConversation(conversation.value.conversationId)
      applyDetails(details)
    }
  }

  return {
    conversation,
    messages,
    progress,
    loading,
    streaming,
    canSend,
    startNewConversation,
    openConversation,
    send,
    retry,
    cancel,
    reset,
    remove,
  }
})

/** 创建一条尚未完成的可重试聊天消息。 */
function createRunningMessage(userMessage: string, clientMessageId: string): ChatMessage {
  return {
    key: clientMessageId,
    clientMessageId,
    userMessage,
    answer: '',
    citations: [],
    retrievalStatus: null,
    resultStatus: null,
    suggestionId: null,
    suggestionExpiresAt: null,
    completedAt: null,
    state: 'running',
    errorMessage: null,
    retryable: false,
  }
}

/** 从未知字段读取字符串，缺失时返回空串。 */
function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** 从未知字段读取布尔值。 */
function booleanValue(value: unknown): boolean {
  return value === true
}

/** 从未知字段读取有限非负数字。 */
function numberValue(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0
}

/** 只接受后端冻结的三个检索状态。 */
function retrievalStatusValue(value: unknown): RetrievalStatus | null {
  return value === 'GROUNDED' || value === 'NO_RELIABLE_KNOWLEDGE' || value === 'RETRIEVAL_FAILED'
    ? value
    : null
}

/** 从事件数据读取单个引用。 */
function citationValue(data: Record<string, unknown>): ChatCitation {
  return {
    citationId: stringValue(data.citationId),
    documentId: stringValue(data.documentId),
    documentTitle: stringValue(data.documentTitle),
    headingPath: stringValue(data.headingPath),
    sourceType: stringValue(data.sourceType),
    sourceCaseId: typeof data.sourceCaseId === 'string' ? data.sourceCaseId : null,
  }
}

/** 从完成事件读取引用数组，并过滤结构无效的条目。 */
function arrayCitationValue(value: unknown): ChatCitation[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(citationValue)
    .filter((item) => item.citationId.length > 0)
}

/** 将未知异常收敛为公开文案，不展示堆栈和请求细节。 */
function safeErrorMessage(error: unknown): string {
  if (error instanceof ApiError || error instanceof ChatStreamError) return error.message
  return '聊天处理失败，请稍后重试'
}
