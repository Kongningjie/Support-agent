import type { KnowledgeSpaceSummary } from './knowledge-space.types'

/** 已解决案例生命周期状态。 */
export type ResolvedCaseStatus =
  'DRAFT' | 'PUBLISHING' | 'PUBLISHED' | 'PUBLISH_FAILED' | 'REJECTED' | 'ARCHIVED'

/** 已解决案例分页摘要。 */
export interface ResolvedCaseSummary {
  caseId: string
  spaceId: string
  space: KnowledgeSpaceSummary
  sourceTicketNo: string | null
  title: string
  status: ResolvedCaseStatus
  version: number
  createdAt: string
  updatedAt: string
}

/** 已解决案例完整审核视图。 */
export interface ResolvedCaseDetails extends ResolvedCaseSummary {
  sourceTicketTitle: string | null
  problem: string
  cause: string
  solution: string
  publishFailureReason: string | null
  rejectionReason: string | null
  archiveReason: string | null
  publishedAt: string | null
  archivedAt: string | null
}
