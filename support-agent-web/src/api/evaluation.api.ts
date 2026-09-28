import { httpClient } from './http.client'
import type { ApiResult } from '@/types/api.types'
import type { EvaluationDatasetKind, EvaluationRun, RetrievalMode } from '@/types/evaluation.types'

/** 启动开发环境内存检索评测。 */
export async function startEvaluation(input: {
  spaceId: string
  datasetKind: EvaluationDatasetKind
  mode: RetrievalMode
  caseIds: string[]
}): Promise<EvaluationRun> {
  const response = await httpClient.post<ApiResult<EvaluationRun>>('/retrieval-evaluations', input)
  return requireData(response.data.data)
}

/** 查询内存评测的当前进度和完成指标。 */
export async function getEvaluation(evaluationRunId: string): Promise<EvaluationRun> {
  const response = await httpClient.get<ApiResult<EvaluationRun>>(
    `/retrieval-evaluations/${evaluationRunId}`,
  )
  return requireData(response.data.data)
}

/** 判断构建时功能开关是否显式启用。 */
export function isEvaluationEnabled(): boolean {
  return import.meta.env.VITE_ENABLE_EVALUATION === 'true'
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('评测接口未返回预期数据')
  return value
}
