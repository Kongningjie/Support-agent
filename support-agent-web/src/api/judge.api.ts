import { httpClient } from './http.client'
import type { ApiResult } from '@/types/api.types'

/** 单维评分与可空的Unicode证据范围。 */
export interface JudgeDimension {
  score: number
  reason: string
  evidenceStart: number | null
  evidenceEnd: number | null
}
/** 单次复测；供应商未报告的Token为null。 */
export interface JudgeAttempt {
  ordinal: number
  dimensions: JudgeDimension[] | null
  failure: string | null
  elapsedMillis: number
  inputTokens: number | null
  outputTokens: number | null
}
/** 运行视图；人工未决时不返回模型分数。 */
export interface JudgeRun {
  runId: string
  sampleId: string
  idempotencyKey: string
  model: string
  promptVersion: string
  status: string
  estimatedInputTokens: number
  attempts: JudgeAttempt[]
  meanAbsoluteErrors: number[]
  exactAgreementRates: number[]
  signedMeanErrors: number[]
  repeatStable: boolean | null
  hiddenUntilHumanDecision: boolean
}
/** 各模型和模板版本的人工一致性统计。 */
export interface JudgeReport {
  model: string
  promptVersion: string
  sampleCount: number
  fullyValidSampleCount: number
  meanAbsoluteErrors: number[]
  exactAgreementRates: number[]
  repeatStabilityRate: number | null
  qualityThresholdPassed: boolean
  truncated: boolean
  sourceModelBiasUnverified: boolean
  samples: JudgeComparison[]
}
/** 聚合报告的数值对照，不携带评分正文。 */
export interface JudgeComparison {
  sampleId: string
  runId: string
  signedMeanErrors: number[]
  meanAbsoluteErrors: number[]
  repeatStable: boolean | null
  failureCount: number
  elapsedMillis: number
  inputTokens: number | null
  outputTokens: number | null
}
/** 三次最长60秒的串行复测，需要独立长请求超时。重试必须复用Key。 */
export async function executeJudge(sampleId: string, idempotencyKey: string): Promise<JudgeRun> {
  const response = await httpClient.post<ApiResult<JudgeRun>>(
    `/admin/human-evaluations/${sampleId}/judge-runs`,
    { idempotencyKey },
    { timeout: 195_000 },
  )
  return required(response.data.data)
}
/** 读取最近二十次运行，服务端执行盲评隐藏。 */
export async function listJudgeRuns(sampleId: string): Promise<JudgeRun[]> {
  const response = await httpClient.get<ApiResult<JudgeRun[]>>(
    `/admin/human-evaluations/${sampleId}/judge-runs`,
  )
  return required(response.data.data)
}
/** 读取按版本分组、按样本去重的校准报告。 */
export async function getJudgeReports(): Promise<JudgeReport[]> {
  const response = await httpClient.get<ApiResult<JudgeReport[]>>('/admin/judge-reports')
  return required(response.data.data)
}
/** 拒绝缺少数据的异常成功响应。 */
function required<T>(value: T | null): T {
  if (value === null) throw new Error('模型评测响应缺少数据')
  return value
}
