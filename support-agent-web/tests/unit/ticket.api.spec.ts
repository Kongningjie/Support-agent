import { afterEach, describe, expect, it, vi } from 'vitest'
import * as ticketApi from '@/api/ticket.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type { TicketDetails, TicketSummary } from '@/types/ticket.types'

afterEach(() => vi.restoreAllMocks())

describe('ticket api', () => {
  it('分页查询只发送已应用的状态、关键词和分页条件', async () => {
    const page: PageResult<TicketSummary> = {
      items: [],
      page: 2,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    }
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: success(page) })
    await ticketApi.listTickets('OPEN', 'MySQL', 2, 50)
    expect(get).toHaveBeenCalledWith('/tickets', {
      params: { status: 'OPEN', keyword: 'MySQL', page: 2, size: 50 },
    })
  })

  it('从会话建议创建草稿时发送建议归属和同一幂等键', async () => {
    const ticket = sampleTicket()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(ticket) })
    await ticketApi.createDraftFromSuggestion('conversation-id', 'suggestion-id', 'same-create-key')
    expect(post).toHaveBeenCalledWith('/tickets/drafts/from-conversation', {
      conversationId: 'conversation-id',
      suggestionId: 'suggestion-id',
      idempotencyKey: 'same-create-key',
    })
  })

  it('解决与关闭动作都携带当前版本和调用方提供的幂等键', async () => {
    const ticket = sampleTicket()
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(ticket) })
    await ticketApi.resolveTicket(ticket.ticketNo, {
      rootCause: '宿主机端口错误',
      solution: '修正端口并验证连接',
      version: 1,
      idempotencyKey: 'resolve-key',
    })
    await ticketApi.closeTicket(ticket.ticketNo, {
      closeReason: '用户确认不再处理',
      version: 1,
      idempotencyKey: 'close-key',
    })
    expect(post).toHaveBeenNthCalledWith(1, `/tickets/${ticket.ticketNo}/resolve`, {
      rootCause: '宿主机端口错误',
      solution: '修正端口并验证连接',
      version: 1,
      idempotencyKey: 'resolve-key',
    })
    expect(post).toHaveBeenNthCalledWith(2, `/tickets/${ticket.ticketNo}/close`, {
      closeReason: '用户确认不再处理',
      version: 1,
      idempotencyKey: 'close-key',
    })
  })
})

/** 创建工单 API 契约测试使用的完整详情。 */
function sampleTicket(): TicketDetails {
  return {
    ticketNo: 'T000000000001',
    title: 'MySQL 连接失败',
    problemDescription: '应用无法连接数据库',
    attemptedActions: '已检查容器状态',
    status: 'DRAFT',
    rootCause: null,
    solution: null,
    closeReason: null,
    version: 0,
    createdAt: '2026-09-27T00:00:00Z',
    updatedAt: '2026-09-27T00:00:00Z',
    resolvedAt: null,
    closedAt: null,
  }
}

/** 包装后端统一成功响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'trace-ticket-test',
    timestamp: '2026-09-27T00:00:00Z',
  }
}
