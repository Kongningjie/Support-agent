/** 固定检索评测模式。 */
export type RetrievalMode = 'BM25_ONLY' | 'VECTOR_ONLY' | 'HYBRID' | 'HYBRID_RERANK'

/** 固定评测数据集用途。 */
export type EvaluationDatasetKind = 'LOCKED_REGRESSION' | 'OPTIMIZATION_DEVELOPMENT'

/** 内存评测运行状态。 */
export type EvaluationStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** 完成后返回的五项检索质量指标。 */
export interface EvaluationMetrics {
  recallAt5: number
  mrrAt10: number
  ndcgAt5: number
  noHitAccuracy: number
  exactTermRecall: number
}

/** 本地内存评测的公开运行快照。 */
export interface EvaluationRun {
  evaluationRunId: string
  mode: RetrievalMode
  status: EvaluationStatus
  completedCases: number
  totalCases: number
  metrics: EvaluationMetrics | null
  failureMessage: string | null
  startedAt: string
  finishedAt: string | null
}
