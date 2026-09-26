/** 后端支持的聊天意图。 */
export type ChatIntent = 'GREETING' | 'SUPPORT_QUERY' | 'TICKET_QUERY' | 'OUT_OF_SCOPE'

/** 知识检索的公开结果状态。 */
export type RetrievalStatus = 'GROUNDED' | 'NO_RELIABLE_KNOWLEDGE' | 'RETRIEVAL_FAILED'

/** 会话当前是否存在运行中的聊天请求。 */
export type ConversationStatus = 'IDLE' | 'RUNNING'

/** 回答引用的公开来源。 */
export interface ChatCitation {
  citationId: string
  documentId: string
  documentTitle: string
  headingPath: string
  sourceType: string
  sourceCaseId: string | null
}

/** 会话列表与写操作返回的公开元数据。 */
export interface ConversationOverview {
  conversationId: string
  status: ConversationStatus
  version: number
  generation: number
  summaryVersion: number
  lastAccessAt: string
  expiresAt: string
}

/** 已成功提交并可再次展示的单轮会话。 */
export interface ConversationTurn {
  turnId: string
  userMessage: string
  answer: string
  intent: ChatIntent
  retrievalStatus: RetrievalStatus | null
  citations: ChatCitation[]
  completedAt: string
  conversationVersion: number
}

/** 会话详情及 Redis 当前保留的最近成功轮次。 */
export interface ConversationDetails {
  conversation: ConversationOverview
  recentTurns: ConversationTurn[]
}

/** 发起首次或后续聊天的请求。 */
export interface ChatStreamRequest {
  conversationId: string | null
  clientMessageId: string
  message: string
  expectedConversationVersion: number | null
}

/** 后端 SSE 业务事件的稳定外层结构。 */
export interface ChatEvent {
  eventId: string
  eventType: string
  runId: string
  conversationId: string
  sequence: number
  timestamp: string
  data: Record<string, unknown>
}

/** 当前界面展示的一轮用户消息和助手结果。 */
export interface ChatMessage {
  key: string
  clientMessageId: string | null
  userMessage: string
  answer: string
  citations: ChatCitation[]
  retrievalStatus: RetrievalStatus | null
  resultStatus: string | null
  suggestionId: string | null
  suggestionExpiresAt: string | null
  completedAt: string | null
  state: 'completed' | 'running' | 'cancelled' | 'failed'
  errorMessage: string | null
  retryable: boolean
}
