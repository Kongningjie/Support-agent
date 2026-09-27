/** 知识空间的读取可见性。 */
export type KnowledgeSpaceVisibility = 'ENTERPRISE' | 'RESTRICTED'

/** 知识空间的生命周期状态。 */
export type KnowledgeSpaceStatus = 'ACTIVE' | 'DISABLED'

/** 当前用户在知识空间内的显式角色；GLOBAL 或企业可见空间可为空。 */
export type SpaceRole = 'READER' | 'EDITOR' | 'MANAGER'

/** 业务资源响应复用的最小知识空间摘要。 */
export interface KnowledgeSpaceSummary {
  spaceId: string
  code: string
  name: string
}

/** 当前用户可见的知识空间安全视图。 */
export interface KnowledgeSpaceView extends KnowledgeSpaceSummary {
  description: string | null
  visibility: KnowledgeSpaceVisibility
  status: KnowledgeSpaceStatus
  systemSpace: boolean
  version: number
  currentUserRole: SpaceRole | null
  createdAt: string
  updatedAt: string
}
