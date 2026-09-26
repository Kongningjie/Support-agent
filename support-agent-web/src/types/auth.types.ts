/** 后端支持的两级用户角色。 */
export type UserRole = 'USER' | 'ADMIN'

/** 后端支持的账号状态。 */
export type UserStatus = 'ACTIVE' | 'DISABLED'

/** 不包含密码、Token 或内部哈希的当前用户摘要。 */
export interface CurrentUser {
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

/** 本地用户名密码登录请求。 */
export interface LoginRequest {
  username: string
  password: string
}

/** 登录成功后仅返回一次的访问凭据和用户摘要。 */
export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: CurrentUser
}

/** 修改本人密码请求。 */
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}
