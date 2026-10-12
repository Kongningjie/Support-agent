import { afterEach, describe, expect, it, vi } from 'vitest'
import * as evaluation from '@/api/human-evaluation.api'
import { httpClient } from '@/api/http.client'
import type { ApiResult } from '@/types/api.types'
import type { HumanSample } from '@/types/human-evaluation.types'

const sampleId = '10000000-0000-0000-0000-000000000001'
const sample: HumanSample = {
  id: sampleId,
  sourceType: 'FIXED',
  sourceRef: 'A8-GROUND-001',
  spaceId: '00000000-0000-0000-0000-000000000001',
  question: '脱敏问题',
  answer: '脱敏回答',
  evidence: '脱敏证据',
  status: 'FIRST_PENDING',
  datasetState: 'NONE',
  datasetVersion: 0,
  createdAt: '2026-10-12T00:00:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('human evaluation api', () => {
  it('受限候选列表按页读取且不请求原始正文', async () => {
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: success([]) })
    await expect(evaluation.feedbackCandidates(2, 20)).resolves.toEqual([])
    expect(get).toHaveBeenCalledWith('/admin/human-evaluations/feedback-candidates', {
      params: { page: 2, size: 20 },
    })
  })

  it('只有明确确认脱敏才在创建请求中提交确认标记', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: success(sample) })
    const input = {
      sourceType: 'FIXED' as const,
      sourceRef: sample.sourceRef,
      spaceId: sample.spaceId,
      question: sample.question,
      answer: sample.answer,
      evidence: sample.evidence,
      sanitizedConfirmed: true,
    }
    await expect(evaluation.createSample(input)).resolves.toEqual(sample)
    expect(post).toHaveBeenCalledWith('/admin/human-evaluations', input)
  })

  it('独立评分和开发集、回归集晋升使用不同写入路径', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: success({ sample, reviews: [], finalScore: null }),
    })
    const score = {
      factual: 2,
      citation: 2,
      completeness: 1,
      actionability: 2,
      clarity: 2,
      reason: '完整性不足',
    }
    await evaluation.submitReview(sampleId, score)
    await evaluation.promoteSample(sampleId, 'DEVELOPMENT')
    await evaluation.promoteSample(sampleId, 'REGRESSION')
    expect(post).toHaveBeenNthCalledWith(1, `/admin/human-evaluations/${sampleId}/reviews`, score)
    expect(post).toHaveBeenNthCalledWith(2, `/admin/human-evaluations/${sampleId}/promotions`, {
      target: 'DEVELOPMENT',
    })
    expect(post).toHaveBeenNthCalledWith(3, `/admin/human-evaluations/${sampleId}/promotions`, {
      target: 'REGRESSION',
    })
  })
})

/** 使用统一 API 成功契约包装测试响应。 */
function success<T>(data: T): ApiResult<T> {
  return {
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'human-review-test',
    timestamp: '2026-10-12T00:00:00Z',
  }
}
