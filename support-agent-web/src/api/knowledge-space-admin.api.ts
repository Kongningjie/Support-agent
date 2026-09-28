import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  KnowledgeSpaceStatus,
  KnowledgeSpaceView,
  KnowledgeSpaceVisibility,
  SpaceMembershipView,
  SpaceRole,
} from '@/types/knowledge-space.types'

/** 管理员按状态、可见性和关键词分页查询空间。 */
export async function listSpaces(filters: {
  status: KnowledgeSpaceStatus | null
  visibility: KnowledgeSpaceVisibility | null
  keyword: string
  page: number
  size: number
}): Promise<PageResult<KnowledgeSpaceView>> {
  const response = await httpClient.get<ApiResult<PageResult<KnowledgeSpaceView>>>(
    '/knowledge-spaces',
    { params: filters },
  )
  return requireData(response.data.data)
}

/** 读取管理员可见的空间详情。 */
export async function getSpace(spaceId: string): Promise<KnowledgeSpaceView> {
  const response = await httpClient.get<ApiResult<KnowledgeSpaceView>>(
    `/knowledge-spaces/${spaceId}`,
  )
  return requireData(response.data.data)
}

/** 创建默认 RESTRICTED 且 ACTIVE 的普通空间。 */
export async function createSpace(
  input: { code: string; name: string; description: string | null },
  idempotencyKey: string,
): Promise<KnowledgeSpaceView> {
  const response = await httpClient.post<ApiResult<KnowledgeSpaceView>>(
    '/admin/knowledge-spaces',
    input,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 按当前版本修改普通空间展示信息和可见性。 */
export async function updateSpace(
  spaceId: string,
  input: {
    name: string
    description: string | null
    visibility: KnowledgeSpaceVisibility
    expectedVersion: number
  },
  idempotencyKey: string,
): Promise<KnowledgeSpaceView> {
  const response = await httpClient.patch<ApiResult<KnowledgeSpaceView>>(
    `/admin/knowledge-spaces/${spaceId}`,
    input,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 按当前版本停用或重新启用普通空间。 */
export async function changeSpaceStatus(
  spaceId: string,
  action: 'disable' | 'enable',
  expectedVersion: number,
  idempotencyKey: string,
): Promise<KnowledgeSpaceView> {
  const response = await httpClient.post<ApiResult<KnowledgeSpaceView>>(
    `/admin/knowledge-spaces/${spaceId}/${action}`,
    { expectedVersion },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 分页读取空间成员，包括已撤销关系。 */
export async function listMembers(
  spaceId: string,
  page: number,
  size: number,
): Promise<PageResult<SpaceMembershipView>> {
  const response = await httpClient.get<ApiResult<PageResult<SpaceMembershipView>>>(
    `/knowledge-spaces/${spaceId}/members`,
    { params: { page, size } },
  )
  return requireData(response.data.data)
}

/** 新增、恢复或按版本修改空间成员角色。 */
export async function putMember(
  spaceId: string,
  userId: string,
  role: SpaceRole,
  expectedVersion: number | null,
  idempotencyKey: string,
): Promise<SpaceMembershipView> {
  const body = expectedVersion === null ? { role } : { role, expectedVersion }
  const response = await httpClient.put<ApiResult<SpaceMembershipView>>(
    `/knowledge-spaces/${spaceId}/members/${userId}`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return requireData(response.data.data)
}

/** 按当前版本撤销空间成员关系。 */
export async function revokeMember(
  spaceId: string,
  userId: string,
  expectedVersion: number,
  idempotencyKey: string,
): Promise<SpaceMembershipView> {
  const response = await httpClient.delete<ApiResult<SpaceMembershipView>>(
    `/knowledge-spaces/${spaceId}/members/${userId}`,
    {
      data: { expectedVersion },
      headers: { 'Idempotency-Key': idempotencyKey },
    },
  )
  return requireData(response.data.data)
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('知识空间接口未返回预期数据')
  return value
}
