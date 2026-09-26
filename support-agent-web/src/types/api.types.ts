/** 后端普通 JSON 接口的统一响应。 */
export interface ApiResult<T> {
  code: string
  message: string
  data: T | null
  traceId: string
  timestamp: string
}

/** 后端从 1 开始计数的分页数据。 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** 前端可安全展示和分支处理的统一请求错误。 */
export class ApiError extends Error {
  /**
   * 创建一个不携带原始请求或敏感 Header 的错误。
   *
   * @param message 可直接向用户展示的安全说明
   * @param status HTTP 状态码；网络未建立连接时为空
   * @param code 后端稳定业务错误码
   * @param traceId 服务端请求追踪标识
   * @param retryable 当前失败是否适合由用户主动重试
   */
  constructor(
    message: string,
    readonly status: number | null,
    readonly code: string,
    readonly traceId: string | null,
    readonly retryable: boolean,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
