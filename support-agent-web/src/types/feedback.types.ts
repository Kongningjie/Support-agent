/** 已冻结的用户帮助程度。 */
export type FeedbackRating = 'HELPFUL' | 'NOT_HELPFUL'

/** 无帮助反馈的预定义原因。 */
export type FeedbackReason = 'INCORRECT' | 'UNSUPPORTED' | 'INCOMPLETE' | 'UNCLEAR' | 'OTHER'

/** 本人当前反馈，不包含回答正文或用户 ID。 */
export interface AnswerFeedback {
  runId: string
  rating: FeedbackRating
  reason: FeedbackReason | null
  note: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** 首次创建省略版本，更正时传当前版本。 */
export interface FeedbackSubmission {
  rating: FeedbackRating
  reason: FeedbackReason | null
  note: string | null
  expectedVersion: number | null
}
