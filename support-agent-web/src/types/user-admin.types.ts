/** 平台用户角色。 */
export type UserRole = 'USER' | 'ADMIN'

/** 平台用户账号状态。 */
export type UserStatus = 'ACTIVE' | 'DISABLED'

/** 管理员可读取且不包含密码和 Token 的用户视图。 */
export interface AdminUserView {
  userId: string
  username: string
  displayName: string
  role: UserRole
  status: UserStatus
  version: number
  mustChangePassword: boolean
  lockedUntil: string | null
  createdAt: string
  updatedAt: string
}
