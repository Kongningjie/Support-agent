import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  CHAT_RESULT_UNCERTAIN,
  ChatCancelledError,
  ChatStreamError,
  streamChat,
} from '@/api/chat.stream'
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
      applyDetails(details, conversation.value?.conversationId === conversationId)
    } finally {
      loading.value = false
    }
  }

  /**
   * 发送新消息；创建 clientMessageId 后交给内部方法，失败重试可复用该 ID。
   *
   * @param rawMessage 用户输入，去除首尾空白后必须为 1～4000 字符
   * @param spaceId 新会话选择或既有会话绑定的知识空间 UUID
   */
  async function send(rawMessage: string, spaceId: string): Promise<void> {
    const message = rawMessage.trim()
    if (!message || message.length > 4000 || !spaceId || !canSend.value) return
    const item = createRunningMessage(message, crypto.randomUUID(), spaceId)
    messages.value.push(item)
    await execute(item)
  }

  /** 重试失败消息并复用原 clientMessageId，避免创建第二个业务动作。 */
  async function retry(key: string): Promise<void> {
    const item = messages.value.find((candidate) => candidate.key === key)
    if (
      !item ||
      !item.clientMessageId ||
      !['failed', 'cancelled'].includes(item.state) ||
      !item.retryable ||
      !canSend.value
    )
      return
    item.answer = ''
    item.citations = []
    item.suggestionId = null
    item.suggestionExpiresAt = null
    item.retrievalStatus = null
    item.resultStatus = null
    item.completedAt = null
    item.errorMessage = null
    item.state = 'running'
    await execute(item)
  }

  /** 主动取消当前流；已收到的片段保留但明确标记为未完成。 */
  function cancel(): void {
    controller.value?.abort()
  }

  /**
   * 使用最新版本和显式目标空间重置会话；冲突时刷新服务端详情。
   *
   * @param spaceId 重置后新会话代际绑定的可读知识空间 UUID
   */
  async function reset(spaceId: string): Promise<void> {
    if (!conversation.value || streaming.value) return
    loading.value = true
    try {
      conversation.value = await conversationApi.resetConversation(
        conversation.value.conversationId,
        conversation.value.version,
        spaceId,
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
          spaceId: conversation.value?.spaceId || item.spaceId,
          conversationId: conversation.value?.conversationId || null,
          clientMessageId: item.clientMessageId as string,
          message: item.userMessage,
          expectedConversationVersion: conversation.value?.version ?? null,
        },
        activeController.signal,
        (event) => {
          if (controller.value === activeController && !activeController.signal.aborted)
            applyEvent(item, event)
        },
      )
      if (activeController.signal.aborted && item.state !== 'completed')
        throw new ChatCancelledError()
    } catch (error) {
      if (
        controller.value !== activeController ||
        !messages.value.some((candidate) => candidate.key === item.key)
      )
        return
      if (item.state === 'completed') return
      if (error instanceof ChatCancelledError) {
        item.state = 'cancelled'
        item.errorMessage = CHAT_RESULT_UNCERTAIN
        item.retryable = true
      } else {
        item.state = 'failed'
        item.errorMessage = safeErrorMessage(error)
        item.retryable =
          error instanceof ChatStreamError
            ? error.retryable
            : error instanceof ApiError &&
              (error.retryable ||
                error.code === 'CHAT_CONVERSATION_BUSY' ||
                error.code === 'CHAT_VERSION_CONFLICT')
        if (error instanceof ApiError && (error.status === 404 || error.status === 410)) {
          conversation.value = null
        }
        if (
          error instanceof ApiError &&
          error.code === 'CHAT_VERSION_CONFLICT' &&
          conversation.value
        ) {
          try {
            const details = await conversationApi.getConversation(conversation.value.conversationId)
            applyDetails(details, true)
          } catch {
            // 刷新失败仍保留原消息 ID，等待用户显式恢复。
          }
        }
      }
    } finally {
      if (controller.value === activeController) {
        controller.value = null
        progress.value = null
      }
    }
  }

  /** 按稳定事件类型更新运行进度、回答、引用、建议与会话版本。 */
  function applyEvent(item: ChatMessage, event: ChatEvent): void {
    if (event.eventType === 'conversation.started') {
      item.runId = event.runId
      const version = numberValue(event.data.conversationVersion)
      conversation.value = {
        conversationId: event.conversationId,
        spaceId: conversation.value?.spaceId || item.spaceId,
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
      item.runId = event.runId
      item.answer = stringValue(event.data.answer)
      item.citations = arrayCitationValue(event.data.citations)
      item.resultStatus = stringValue(event.data.resultStatus)
      item.state = 'completed'
      item.completedAt = event.timestamp
      item.errorMessage = null
      item.retryable = false
      if (conversation.value) {
        conversation.value = {
          ...conversation.value,
          status: 'IDLE',
          // 旧轮次重放携带原成功版本，不能覆盖 started 给出的当前会话版本。
          version: Math.max(
            conversation.value.version,
            numberValue(event.data.conversationVersion),
          ),
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

  /** 以服务端轮次恢复成功结果；同会话保留待确认消息，代际改变后禁止旧消息重试。 */
  function applyDetails(details: ConversationDetails, preservePending = false): void {
    const sameGeneration =
      preservePending && conversation.value?.generation === details.conversation.generation
    const savedRuns = new Set(details.recentTurns.map((turn) => turn.runId))
    const candidates = preservePending
      ? messages.value.filter((item) => item.clientMessageId && item.state !== 'completed')
      : []
    const pending = sameGeneration
      ? candidates.filter((item) => !savedRuns.has(item.runId || ''))
      : candidates.map((item): ChatMessage => ({
          ...item,
          answer: '',
          citations: [],
          suggestionId: null,
          suggestionExpiresAt: null,
          completedAt: null,
          retrievalStatus: null,
          resultStatus: null,
          state: 'failed',
          retryable: false,
          errorMessage: '会话已重置，这条旧代际消息不能重试；请重新发送问题',
        }))
    conversation.value = details.conversation
    messages.value = details.recentTurns.map((turn) => ({
      key: turn.turnId,
      runId: turn.runId,
      clientMessageId: null,
      spaceId: details.conversation.spaceId,
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
    messages.value.push(...pending)
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
function createRunningMessage(
  userMessage: string,
  clientMessageId: string,
  spaceId: string,
): ChatMessage {
  return {
    key: clientMessageId,
    runId: null,
    clientMessageId,
    spaceId,
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
    spaceId: stringValue(data.spaceId),
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
