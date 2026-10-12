/** 人工评测来源，只接受已有固定用例或无帮助反馈。 */
export type HumanSourceType = 'FIXED' | 'FEEDBACK'

/** 一份样本的双人盲评与复核状态。 */
export type HumanReviewStatus =
  'FIRST_PENDING' | 'SECOND_PENDING' | 'ARBITRATION_PENDING' | 'DECIDED'

/** 独立人工开发集、回归集层级。 */
export type HumanDatasetState = 'NONE' | 'DEVELOPMENT' | 'REGRESSION'

/** 受限反馈候选，不包含用户说明和回答正文。 */
export interface HumanFeedbackCandidate {
  runId: string
  conversationId: string
  reason: string
  createdAt: string
}

/** 现有固定回答用例的标识、类别和问题。 */
export interface HumanFixedCandidate {
  caseId: string
  category: string
  question: string
}

/** 管理员显式创建的脱敏评测副本。 */
export interface HumanSample {
  id: string
  sourceType: HumanSourceType
  sourceRef: string
  spaceId: string
  question: string
  answer: string
  evidence: string
  status: HumanReviewStatus
  datasetState: HumanDatasetState
  datasetVersion: number
  createdAt: string
}

/** 五维人工评分，分数均为 0～2。 */
export interface HumanScore {
  factual: number
  citation: number
  completeness: number
  actionability: number
  clarity: number
}

/** 一名管理员提交且不可更改的评分。 */
export interface HumanReview {
  reviewerId: string
  role: 'FIRST' | 'SECOND' | 'ARBITER'
  score: HumanScore
  reason: string
  createdAt: string
}

/** 样本详情；结论形成前仅包含当前管理员自己的评分。 */
export interface HumanDetail {
  sample: HumanSample
  reviews: HumanReview[]
  finalScore: HumanScore | null
}

/** 显式提交并确认脱敏的样本内容。 */
export interface HumanCreateInput {
  sourceType: HumanSourceType
  sourceRef: string
  spaceId: string
  question: string
  answer: string
  evidence: string
  sanitizedConfirmed: boolean
}
