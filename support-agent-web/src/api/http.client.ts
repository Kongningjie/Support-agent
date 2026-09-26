import axios from 'axios'
import type { AxiosError } from 'axios'
import type { ApiResult } from '@/types/api.types'
import { ApiError } from '@/types/api.types'
import { clearAccessToken, readAccessToken } from './auth.session'

/** 浏览器收到认证失效响应时派发的本地事件名称。 */
export const AUTH_UNAUTHORIZED_EVENT = 'support-agent:unauthorized'

const DEFAULT_MESSAGE = '请求失败，请稍后重试'
const NETWORK_MESSAGE = '无法连接服务，请确认后端已启动后重试'

/** 项目内普通 REST 请求共享的唯一 Axios 实例。 */
export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15_000,
  headers: {
    Accept: 'application/json',
  },
})

httpClient.interceptors.request.use((config) => {
  const token = readAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

httpClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error)
    if (apiError.status === 401) {
      clearAccessToken()
      window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
    }
    return Promise.reject(apiError)
  },
)

/**
 * 把 Axios 或未知异常收敛为可安全展示的统一错误。
 *
 * @param error Axios 抛出的原始异常或未知异常
 * @returns 不包含请求配置、Header 和内部堆栈的前端错误
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (!axios.isAxiosError(error)) {
    return new ApiError(DEFAULT_MESSAGE, null, 'CLIENT_UNKNOWN_ERROR', null, false)
  }

  const axiosError = error as AxiosError<ApiResult<unknown>>
  if (!axiosError.response) {
    return new ApiError(NETWORK_MESSAGE, null, 'NETWORK_ERROR', null, true)
  }

  const { status, data } = axiosError.response
  const body = isApiResult(data) ? data : null
  return new ApiError(
    body?.message || messageForStatus(status),
    status,
    body?.code || `HTTP_${status}`,
    body?.traceId || null,
    status === 429 || status === 502 || status === 503 || status >= 500,
  )
}

/** 根据 HTTP 状态返回不泄露内部信息的默认说明。 */
function messageForStatus(status: number): string {
  const messages: Record<number, string> = {
    400: '请求参数不符合要求',
    401: '登录状态已失效，请重新登录',
    403: '当前账号无权执行此操作',
    404: '请求的资源不存在',
    409: '数据状态已发生变化，请刷新后重试',
    422: '提交内容未通过安全校验',
    429: '操作过于频繁，请稍后重试',
    502: '上游服务返回无效响应，请稍后重试',
    503: '依赖服务暂不可用，请稍后重试',
  }
  return messages[status] || DEFAULT_MESSAGE
}

/** 检查未知响应是否满足后端统一错误的最小公开结构。 */
function isApiResult(value: unknown): value is ApiResult<unknown> {
  if (!value || typeof value !== 'object') {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' &&
    typeof candidate.message === 'string' &&
    typeof candidate.traceId === 'string'
  )
}
