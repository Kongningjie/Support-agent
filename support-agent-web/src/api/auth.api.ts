import type { ApiResult } from '@/types/api.types'
import { ApiError } from '@/types/api.types'
import type {
  ChangePasswordRequest,
  CurrentUser,
  LoginRequest,
  LoginResponse,
} from '@/types/auth.types'
import { httpClient } from './http.client'

/** 使用本地用户名和密码换取一次性返回的不透明 Token。 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await httpClient.post<ApiResult<LoginResponse>>('/auth/login', request)
  return requireData(response.data)
}

/** 查询当前 Token 对应的安全用户摘要。 */
export async function getCurrentUser(): Promise<CurrentUser> {
  const response = await httpClient.get<ApiResult<CurrentUser>>('/users/me')
  return requireData(response.data)
}

/** 撤销当前请求使用的不透明 Token。 */
export async function logout(): Promise<void> {
  await httpClient.post<ApiResult<null>>('/auth/logout')
}

/** 修改本人密码；后端成功后会撤销该用户全部旧 Token。 */
export async function changePassword(request: ChangePasswordRequest): Promise<CurrentUser> {
  const response = await httpClient.post<ApiResult<CurrentUser>>('/users/me/password', request)
  return requireData(response.data)
}

/** 撤销本人全部有效 Token，包括当前 Token。 */
export async function revokeAllTokens(): Promise<void> {
  await httpClient.post<ApiResult<null>>('/users/me/tokens/revoke-all')
}

/** 从成功响应中读取必需数据，防止错误响应被误当成成功。 */
function requireData<T>(result: ApiResult<T>): T {
  if (result.code !== 'SUCCESS' || result.data === null) {
    throw new ApiError(
      '服务响应缺少必要数据，请稍后重试',
      502,
      'CLIENT_INVALID_API_RESPONSE',
      result.traceId || null,
      true,
    )
  }
  return result.data
}
