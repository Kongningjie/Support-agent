/** 长期记忆类别。 */
export type MemoryType = 'PREFERENCE' | 'CONSTRAINT' | 'ENVIRONMENT'

/** 长期记忆生命周期状态。 */
export type MemoryStatus = 'PROPOSED' | 'ACTIVE' | 'REVOKED'

/** 用户长期记忆开关及乐观锁版本。 */
export interface MemorySettings {
  enabled: boolean
  version: number
}

/** 用户本人可见的长期记忆。 */
export interface UserMemory {
  memoryId: string
  memoryType: MemoryType
  content: string
  status: MemoryStatus
  pinned: boolean
  sourceConversationId: string
  sourceTurnId: string
  expiresAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** 更正长期记忆的可选字段和并发版本。 */
export interface ReviseMemoryRequest {
  content?: string
  expiresAt?: string | null
  clearExpiresAt: boolean
  pinned?: boolean
  expectedVersion: number
}
