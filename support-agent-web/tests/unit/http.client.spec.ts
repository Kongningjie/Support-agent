import axios from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/types/api.types'
import { writeAccessToken, readAccessToken } from '@/api/auth.session'
import { AUTH_UNAUTHORIZED_EVENT, httpClient, toApiError } from '@/api/http.client'

describe('统一 HTTP 错误映射', () => {
  it('保留后端公开错误码、说明和 traceId', () => {
    const source = new axios.AxiosError('internal', 'ERR_BAD_REQUEST', undefined, undefined, {
      data: {
        code: 'AUTH_INVALID_CREDENTIALS',
        message: '用户名或密码错误',
        data: null,
        traceId: 'trace-123',
        timestamp: '2026-09-26T00:00:00Z',
      },
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config: { headers: new axios.AxiosHeaders() },
    })

    const result = toApiError(source)

    expect(result).toMatchObject({
      status: 401,
      code: 'AUTH_INVALID_CREDENTIALS',
      message: '用户名或密码错误',
      traceId: 'trace-123',
      retryable: false,
    })
  })

  it('把无响应异常映射为可重试网络错误', () => {
    const result = toApiError(new axios.AxiosError('socket details', 'ERR_NETWORK'))

    expect(result).toMatchObject({
      status: null,
      code: 'NETWORK_ERROR',
      retryable: true,
    })
    expect(result.message).not.toContain('socket')
  })

  it('不会覆盖已经收敛的 ApiError', () => {
    const source = new ApiError('公开说明', 409, 'COMMON_CONFLICT', 'trace-409', false)

    expect(toApiError(source)).toBe(source)
  })

  it('收到 401 时清除 Token 并广播认证失效事件', async () => {
    writeAccessToken('expired-token')
    const listener = vi.fn()
    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, listener, { once: true })

    await expect(
      httpClient.get('/protected', {
        adapter: (config) =>
          Promise.reject(
            new axios.AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
              data: {
                code: 'AUTH_UNAUTHORIZED',
                message: '登录状态已失效',
                data: null,
                traceId: 'trace-401',
                timestamp: '2026-09-26T00:00:00Z',
              },
              status: 401,
              statusText: 'Unauthorized',
              headers: {},
              config,
            }),
          ),
      }),
    ).rejects.toMatchObject({ status: 401, code: 'AUTH_UNAUTHORIZED' })

    expect(readAccessToken()).toBeNull()
    expect(listener).toHaveBeenCalledOnce()
  })
})
