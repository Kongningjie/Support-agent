import { httpClient } from './http.client'
import type { ApiResult } from '@/types/api.types'
import type { AnswerFeedback, FeedbackSubmission } from '@/types/feedback.types'

/** 查询本人对指定成功运行的当前反馈，尚未提交时后端返回 404。 */
export async function getFeedback(runId: string): Promise<AnswerFeedback> {
  const response = await httpClient.get<ApiResult<AnswerFeedback>>(`/answer-feedback/${runId}`)
  if (!response.data.data) throw new Error('反馈接口未返回预期数据')
  return response.data.data
}

/** 首次提交或使用当前版本更正本人反馈。 */
export async function putFeedback(
  runId: string,
  submission: FeedbackSubmission,
): Promise<AnswerFeedback> {
  const response = await httpClient.put<ApiResult<AnswerFeedback>>(
    `/answer-feedback/${runId}`,
    submission,
  )
  if (!response.data.data) throw new Error('反馈接口未返回预期数据')
  return response.data.data
}

/** 按当前版本删除本人反馈内容。 */
export async function deleteFeedback(runId: string, expectedVersion: number): Promise<void> {
  await httpClient.delete<ApiResult<null>>(`/answer-feedback/${runId}`, {
    params: { expectedVersion },
  })
}
