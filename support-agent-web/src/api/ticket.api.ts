import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  CloseTicketRequest,
  CreateTicketDraftRequest,
  ResolveTicketRequest,
  ReviseTicketDraftRequest,
  TicketDetails,
  TicketStatus,
  TicketSummary,
  VersionedTicketActionRequest,
} from '@/types/ticket.types'

/** 按后端授权范围分页查询工单，可选状态与关键词筛选。 */
export async function listTickets(
  status: TicketStatus | null,
  keyword: string,
  page: number,
  size: number,
): Promise<PageResult<TicketSummary>> {
  const response = await httpClient.get<ApiResult<PageResult<TicketSummary>>>('/tickets', {
    params: { status: status || undefined, keyword: keyword || undefined, page, size },
  })
  return requireData(response.data.data, '工单列表')
}

/** 按公开工单编号查询当前用户可访问的详情。 */
export async function getTicket(ticketNo: string): Promise<TicketDetails> {
  const response = await httpClient.get<ApiResult<TicketDetails>>(`/tickets/${ticketNo}`)
  return requireData(response.data.data, '工单详情')
}

/** 使用人工输入与可复用幂等键创建草稿。 */
export async function createDraft(request: CreateTicketDraftRequest): Promise<TicketDetails> {
  const response = await httpClient.post<ApiResult<TicketDetails>>('/tickets/drafts', request)
  return requireData(response.data.data, '新建工单')
}

/** 显式消费会话建议并生成唯一草稿。 */
export async function createDraftFromSuggestion(
  conversationId: string,
  suggestionId: string,
  idempotencyKey: string,
): Promise<TicketDetails> {
  const response = await httpClient.post<ApiResult<TicketDetails>>(
    '/tickets/drafts/from-conversation',
    { conversationId, suggestionId, idempotencyKey },
  )
  return requireData(response.data.data, '建议工单')
}

/** 使用当前版本修改尚未提交的草稿正文。 */
export async function reviseDraft(
  ticketNo: string,
  request: ReviseTicketDraftRequest,
): Promise<TicketDetails> {
  const response = await httpClient.put<ApiResult<TicketDetails>>(
    `/tickets/${ticketNo}/draft`,
    request,
  )
  return requireData(response.data.data, '修改工单')
}

/** 幂等地把草稿提交为开放工单。 */
export async function submitTicket(
  ticketNo: string,
  request: VersionedTicketActionRequest,
): Promise<TicketDetails> {
  const response = await httpClient.post<ApiResult<TicketDetails>>(
    `/tickets/${ticketNo}/submit`,
    request,
  )
  return requireData(response.data.data, '提交工单')
}

/** 使用人工确认的根因与方案解决开放工单。 */
export async function resolveTicket(
  ticketNo: string,
  request: ResolveTicketRequest,
): Promise<TicketDetails> {
  const response = await httpClient.post<ApiResult<TicketDetails>>(
    `/tickets/${ticketNo}/resolve`,
    request,
  )
  return requireData(response.data.data, '解决工单')
}

/** 使用人工原因关闭草稿或开放工单。 */
export async function closeTicket(
  ticketNo: string,
  request: CloseTicketRequest,
): Promise<TicketDetails> {
  const response = await httpClient.post<ApiResult<TicketDetails>>(
    `/tickets/${ticketNo}/close`,
    request,
  )
  return requireData(response.data.data, '关闭工单')
}

/** 拒绝成功响应缺少必须数据的契约异常。 */
function requireData<T>(data: T | null, operation: string): T {
  if (data === null) {
    throw new Error(`${operation}接口未返回预期数据`)
  }
  return data
}
