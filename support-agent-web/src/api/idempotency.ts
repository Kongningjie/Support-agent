import { ApiError } from '@/types/api.types'

/**
 * 判断写请求是否可能已经到达服务端但客户端未收到响应。
 *
 * 只有没有 HTTP 响应的网络错误需要复用原幂等键；明确的 4xx/5xx 响应允许下一次人工动作生成新键。
 */
export function hasUncertainOutcome(error: unknown): boolean {
  return error instanceof ApiError && error.status === null && error.code === 'NETWORK_ERROR'
}
