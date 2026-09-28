import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type { AdminUserView, UserRole, UserStatus } from '@/types/user-admin.types'

/** 管理员按角色和状态分页查询用户。 */
export async function listUsers(filters: {
  role: UserRole | null
  status: UserStatus | null
  page: number
  size: number
}): Promise<PageResult<AdminUserView>> {
  const response = await httpClient.get<ApiResult<PageResult<AdminUserView>>>('/admin/users', {
    params: filters,
  })
  return requireData(response.data.data)
}

/** 管理员创建本地用户；初始密码不会由后端回显。 */
export async function createUser(input: {
  username: string
  displayName: string
  password: string
  role: UserRole
  idempotencyKey: string
}): Promise<AdminUserView> {
  const response = await httpClient.post<ApiResult<AdminUserView>>('/admin/users', input)
  return requireData(response.data.data)
}

/** 按当前版本修改用户角色。 */
export async function changeUserRole(
  userId: string,
  role: UserRole,
  version: number,
): Promise<AdminUserView> {
  const response = await httpClient.patch<ApiResult<AdminUserView>>(`/admin/users/${userId}/role`, {
    role,
    version,
  })
  return requireData(response.data.data)
}

/** 按当前版本启用或禁用用户。 */
export async function changeUserStatus(
  userId: string,
  status: UserStatus,
  version: number,
): Promise<AdminUserView> {
  const response = await httpClient.patch<ApiResult<AdminUserView>>(
    `/admin/users/${userId}/status`,
    { status, version },
  )
  return requireData(response.data.data)
}

/** 设置一次性密码并要求目标用户下次登录后改密。 */
export async function resetUserPassword(
  userId: string,
  newPassword: string,
  version: number,
): Promise<AdminUserView> {
  const response = await httpClient.post<ApiResult<AdminUserView>>(
    `/admin/users/${userId}/password-reset`,
    { newPassword, version },
  )
  return requireData(response.data.data)
}

/** 清除用户临时锁定和用户名失败计数。 */
export async function unlockUser(userId: string, version: number): Promise<AdminUserView> {
  const response = await httpClient.post<ApiResult<AdminUserView>>(
    `/admin/users/${userId}/unlock`,
    {
      version,
    },
  )
  return requireData(response.data.data)
}

/** 撤销目标用户的全部有效 Token。 */
export async function revokeAllUserTokens(userId: string): Promise<void> {
  await httpClient.post<ApiResult<null>>(`/admin/users/${userId}/tokens/revoke-all`)
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('用户治理接口未返回预期数据')
  return value
}
