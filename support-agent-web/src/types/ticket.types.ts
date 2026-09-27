import type { KnowledgeSpaceSummary } from './knowledge-space.types'

/** 后端冻结的工单生命周期状态。 */
export type TicketStatus = 'DRAFT' | 'OPEN' | 'RESOLVED' | 'CLOSED'

/** 工单分页列表中的只读摘要。 */
export interface TicketSummary {
  ticketNo: string
  spaceId: string
  space: KnowledgeSpaceSummary
  title: string
  status: TicketStatus
  version: number
  createdAt: string
  updatedAt: string
}

/** 工单详情；解决与关闭字段只在对应终态存在。 */
export interface TicketDetails extends TicketSummary {
  problemDescription: string
  attemptedActions: string | null
  rootCause: string | null
  solution: string | null
  closeReason: string | null
  resolvedAt: string | null
  closedAt: string | null
}

/** 手工创建草稿需要的人工事实字段。 */
export interface CreateTicketDraftRequest {
  spaceId: string
  title: string
  problemDescription: string
  attemptedActions: string | null
  idempotencyKey: string
}

/** 修改草稿时携带当前乐观锁版本。 */
export interface ReviseTicketDraftRequest {
  title: string
  problemDescription: string
  attemptedActions: string | null
  version: number
}

/** 只包含版本与幂等键的状态动作。 */
export interface VersionedTicketActionRequest {
  version: number
  idempotencyKey: string
}

/** 关闭草稿或开放工单的人工原因。 */
export interface CloseTicketRequest extends VersionedTicketActionRequest {
  closeReason: string
}

/** 解决开放工单时人工确认的根因与实际方案。 */
export interface ResolveTicketRequest extends VersionedTicketActionRequest {
  rootCause: string
  solution: string
}
