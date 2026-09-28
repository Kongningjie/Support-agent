import { randomUUID } from 'node:crypto'
import type { APIRequestContext } from '@playwright/test'

interface ApiResult<T> {
  code: string
  message: string
  data: T | null
}

interface LoginData {
  accessToken: string
}

interface PageResult<T> {
  items: T[]
  totalPages: number
}

interface AdminUserView {
  userId: string
  username: string
  status: 'ACTIVE' | 'DISABLED'
  version: number
}

/** Playwright 真实联调使用的固定账号配置。 */
export interface E2eCredentials {
  adminUsername: string
  adminPassword: string
  userUsername: string
  userPassword: string
}

/** 从测试进程环境读取非 Vite 凭据，缺少时立即失败且不输出秘密。 */
export function readE2eCredentials(): E2eCredentials {
  return {
    adminUsername: required('E2E_ADMIN_USERNAME'),
    adminPassword: required('E2E_ADMIN_PASSWORD'),
    userUsername: required('E2E_USER_USERNAME'),
    userPassword: required('E2E_USER_PASSWORD'),
  }
}

/** 派生仅在本轮验收期间使用的新密码，保持 BCrypt 72 字节上限。 */
export function changedPassword(initialPassword: string): string {
  return `${initialPassword.slice(0, 58)}-Changed1!`
}

/**
 * 确保固定普通用户存在、启用并被重置为指定一次性密码。
 *
 * @param request Playwright 真实 HTTP 请求上下文
 * @param credentials 管理员与普通用户测试凭据
 */
export async function prepareUser(
  request: APIRequestContext,
  credentials: E2eCredentials,
): Promise<void> {
  const token = await login(request, credentials.adminUsername, credentials.adminPassword)
  let user = await findUser(request, token, credentials.userUsername)
  if (!user) {
    const response = await request.post('/api/v1/admin/users', {
      headers: bearer(token),
      data: {
        username: credentials.userUsername,
        displayName: 'E2E 验收用户',
        password: credentials.userPassword,
        role: 'USER',
        idempotencyKey: randomUUID(),
      },
    })
    user = await data<AdminUserView>(response, '创建 E2E 用户')
  }
  if (user.status === 'DISABLED') {
    const response = await request.patch(`/api/v1/admin/users/${user.userId}/status`, {
      headers: bearer(token),
      data: { status: 'ACTIVE', version: user.version },
    })
    user = await data<AdminUserView>(response, '启用 E2E 用户')
  }
  const reset = await request.post(`/api/v1/admin/users/${user.userId}/password-reset`, {
    headers: bearer(token),
    data: { newPassword: credentials.userPassword, version: user.version },
  })
  await data<AdminUserView>(reset, '重置 E2E 用户密码')
}

/** 使用真实认证接口获取只存在于当前测试进程内的 Token。 */
async function login(
  request: APIRequestContext,
  username: string,
  password: string,
): Promise<string> {
  const response = await request.post('/api/v1/auth/login', { data: { username, password } })
  return (await data<LoginData>(response, '管理员登录')).accessToken
}

/** 分页查找专用 E2E 用户，避免修改非测试账号。 */
async function findUser(
  request: APIRequestContext,
  token: string,
  username: string,
): Promise<AdminUserView | null> {
  for (let page = 1; page <= 20; page += 1) {
    const response = await request.get('/api/v1/admin/users', {
      headers: bearer(token),
      params: { page, size: 100 },
    })
    const result = await data<PageResult<AdminUserView>>(response, '查找 E2E 用户')
    const found = result.items.find((item) => item.username === username)
    if (found) return found
    if (page >= result.totalPages) return null
  }
  throw new Error('E2E 用户分页超过安全查找上限')
}

/** 读取统一响应数据并在失败时只输出状态和公开业务码。 */
async function data<T>(
  response: Awaited<ReturnType<APIRequestContext['get']>>,
  operation: string,
): Promise<T> {
  const body = (await response.json()) as ApiResult<T>
  if (!response.ok() || body.code !== 'SUCCESS' || body.data === null) {
    throw new Error(`${operation}失败：HTTP ${response.status()} / ${body.code}`)
  }
  return body.data
}

/** 构造只在当前请求内使用的 Bearer Header。 */
function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` }
}

/** 读取必需环境变量但不把取值写入异常。 */
function required(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`缺少必需的测试进程环境变量 ${name}`)
  return value
}
