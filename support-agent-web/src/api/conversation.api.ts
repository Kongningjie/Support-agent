import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type { ConversationDetails, ConversationOverview } from '@/types/chat.types'

/** 分页读取当前用户可访问的会话。 */
export async function listConversations(
  page = 1,
  size = 20,
): Promise<PageResult<ConversationOverview>> {
  const response = await httpClient.get<ApiResult<PageResult<ConversationOverview>>>(
    '/conversations',
    {
      params: { page, size },
    },
  )
  return requireData(response.data.data)
}

/** 按公开 UUID 读取会话详情和最近成功轮次。 */
export async function getConversation(conversationId: string): Promise<ConversationDetails> {
  const response = await httpClient.get<ApiResult<ConversationDetails>>(
    `/conversations/${conversationId}`,
  )
  return requireData(response.data.data)
}

/** 使用当前版本原子重置会话。 */
export async function resetConversation(
  conversationId: string,
  expectedVersion: number,
): Promise<ConversationOverview> {
  const response = await httpClient.post<ApiResult<ConversationOverview>>(
    `/conversations/${conversationId}/reset`,
    { expectedVersion },
  )
  return requireData(response.data.data)
}

/** 使用当前版本永久删除会话。 */
export async function deleteConversation(
  conversationId: string,
  expectedVersion: number,
): Promise<void> {
  await httpClient.delete<ApiResult<null>>(`/conversations/${conversationId}`, {
    params: { expectedVersion },
  })
}

/** 拒绝后端成功响应缺少必须数据的契约异常。 */
function requireData<T>(data: T | null): T {
  if (data === null) {
    throw new Error('会话接口未返回预期数据')
  }
  return data
}
