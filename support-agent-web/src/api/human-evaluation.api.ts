import { httpClient } from './http.client'
import type { ApiResult } from '@/types/api.types'
import type {
  HumanCreateInput,
  HumanDatasetState,
  HumanDetail,
  HumanFeedbackCandidate,
  HumanFixedCandidate,
  HumanSample,
  HumanScore,
} from '@/types/human-evaluation.types'

const path = '/admin/human-evaluations'

/** 按创建时间分页读取无帮助反馈的最小候选元数据。 */
export async function feedbackCandidates(page = 1, size = 20): Promise<HumanFeedbackCandidate[]> {
  const response = await httpClient.get<ApiResult<HumanFeedbackCandidate[]>>(
    `${path}/feedback-candidates`,
    { params: { page, size } },
  )
  return required(response.data.data)
}

/** 读取阶段 8 固定回答用例的候选标识和问题。 */
export async function fixedCandidates(): Promise<HumanFixedCandidate[]> {
  const response = await httpClient.get<ApiResult<HumanFixedCandidate[]>>(
    `${path}/fixed-candidates`,
  )
  return required(response.data.data)
}

/** 分页读取已脱敏样本。 */
export async function listSamples(page = 1, size = 20): Promise<HumanSample[]> {
  const response = await httpClient.get<ApiResult<HumanSample[]>>(path, { params: { page, size } })
  return required(response.data.data)
}

/** 按盲评权限读取样本和当前可见评分。 */
export async function getSample(sampleId: string): Promise<HumanDetail> {
  const response = await httpClient.get<ApiResult<HumanDetail>>(`${path}/${sampleId}`)
  return required(response.data.data)
}

/** 在管理员人工确认脱敏后保存样本副本。 */
export async function createSample(input: HumanCreateInput): Promise<HumanSample> {
  const response = await httpClient.post<ApiResult<HumanSample>>(path, input)
  return required(response.data.data)
}

/** 提交一份不可更改的五维盲评分。 */
export async function submitReview(
  sampleId: string,
  input: HumanScore & { reason: string },
): Promise<HumanDetail> {
  const response = await httpClient.post<ApiResult<HumanDetail>>(
    `${path}/${sampleId}/reviews`,
    input,
  )
  return required(response.data.data)
}

/** 显式纳入开发集或另行审批晋升回归集。 */
export async function promoteSample(
  sampleId: string,
  target: Exclude<HumanDatasetState, 'NONE'>,
): Promise<HumanDetail> {
  const response = await httpClient.post<ApiResult<HumanDetail>>(`${path}/${sampleId}/promotions`, {
    target,
  })
  return required(response.data.data)
}

/** 拒绝没有数据的异常成功响应。 */
function required<T>(value: T | null): T {
  if (value === null) throw new Error('人工评测接口未返回预期数据')
  return value
}
