import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type { KnowledgeSpaceView } from '@/types/knowledge-space.types'

/** 分页读取当前用户可读的活动知识空间。 */
export async function listReadableKnowledgeSpaces(
  page = 1,
  size = 100,
): Promise<PageResult<KnowledgeSpaceView>> {
  const response = await httpClient.get<ApiResult<PageResult<KnowledgeSpaceView>>>(
    '/knowledge-spaces',
    { params: { page, size } },
  )
  if (response.data.data === null) {
    throw new Error('知识空间接口未返回预期数据')
  }
  return response.data.data
}

/** 读取当前用户全部可读活动空间，保留服务端稳定排序。 */
export async function listAllReadableKnowledgeSpaces(): Promise<KnowledgeSpaceView[]> {
  const first = await listReadableKnowledgeSpaces(1, 100)
  const spaces = [...first.items]
  for (let page = 2; page <= first.totalPages; page += 1) {
    const next = await listReadableKnowledgeSpaces(page, 100)
    spaces.push(...next.items)
  }
  return spaces.filter((space) => space.status === 'ACTIVE')
}
