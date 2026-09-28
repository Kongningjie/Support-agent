import type { KnowledgeSpaceSummary } from './knowledge-space.types'

/** 托管知识文档的服务端输入类型。 */
export type DocumentInputType = 'MARKDOWN_FILE' | 'TEXT_FILE' | 'DIRECT_TEXT'

/** 托管知识文档生命周期状态。 */
export type ManagedDocumentStatus = 'DRAFT' | 'INDEXING' | 'PUBLISHED' | 'FAILED' | 'ARCHIVED'

/** 知识文档分页摘要。 */
export interface ManagedDocumentSummary {
  documentId: string
  spaceId: string
  space: KnowledgeSpaceSummary
  title: string
  inputType: DocumentInputType
  status: ManagedDocumentStatus
  version: number
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

/** 包含正文与失败原因的知识文档详情。 */
export interface ManagedDocumentDetails extends ManagedDocumentSummary {
  originalFileName: string | null
  mediaType: string
  rawContent: string
  contentHash: string
  indexFailureReason: string | null
  archiveReason: string | null
  archivedAt: string | null
}

/** 发布或归档动作返回的文档和异步任务。 */
export interface ManagedDocumentAction {
  document: ManagedDocumentDetails
  taskId: string
}
