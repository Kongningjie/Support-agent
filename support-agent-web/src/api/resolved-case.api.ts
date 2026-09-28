import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  ResolvedCaseDetails,
  ResolvedCaseStatus,
  ResolvedCaseSummary,
} from '@/types/resolved-case.types'

/** 按空间、状态、来源工单和关键词分页读取案例。 */
export async function listCases(filters: {
  status: ResolvedCaseStatus | null
  sourceTicketNo: string
  keyword: string
  spaceId: string | null
  page: number
  size: number
}): Promise<PageResult<ResolvedCaseSummary>> {
  const response = await httpClient.get<ApiResult<PageResult<ResolvedCaseSummary>>>(
    '/resolved-cases',
    { params: filters },
  )
  return requireData(response.data.data)
}

/** 读取案例完整审核内容。 */
export async function getCase(caseId: string): Promise<ResolvedCaseDetails> {
  const response = await httpClient.get<ApiResult<ResolvedCaseDetails>>(`/resolved-cases/${caseId}`)
  return requireData(response.data.data)
}

/** 按版本保存案例完整草稿。 */
export async function reviseCase(
  caseId: string,
  input: { title: string; problem: string; cause: string; solution: string; version: number },
): Promise<ResolvedCaseDetails> {
  const response = await httpClient.put<ApiResult<ResolvedCaseDetails>>(
    `/resolved-cases/${caseId}/draft`,
    input,
  )
  return requireData(response.data.data)
}

/** 发布、拒绝或归档案例并复用当前用户动作的幂等键。 */
export async function actOnCase(
  caseId: string,
  action: 'publish' | 'reject' | 'archive',
  input: Record<string, string | number>,
): Promise<ResolvedCaseDetails> {
  const response = await httpClient.post<ApiResult<ResolvedCaseDetails>>(
    `/resolved-cases/${caseId}/${action}`,
    input,
  )
  return requireData(response.data.data)
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('案例治理接口未返回预期数据')
  return value
}
