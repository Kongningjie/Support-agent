import { afterEach, describe, expect, it, vi } from 'vitest'
import { executeJudge, listJudgeRuns, getJudgeReports } from '@/api/judge.api'
import { httpClient } from '@/api/http.client'

afterEach(() => vi.restoreAllMocks())
describe('judge api', () => {
  it('长请求保留同一幂等标识，结果不确定时可以重试', async () => {
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValue({ data: { data: { status: 'RUNNING' } } })
    await executeJudge('sample', 'same-key')
    await executeJudge('sample', 'same-key')
    expect(post).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenLastCalledWith(
      '/admin/human-evaluations/sample/judge-runs',
      { idempotencyKey: 'same-key' },
      { timeout: 195_000 },
    )
  })
  it('运行与人工校准使用独立只读接口', async () => {
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { data: [] } })
    await listJudgeRuns('sample')
    await getJudgeReports()
    expect(get).toHaveBeenNthCalledWith(1, '/admin/human-evaluations/sample/judge-runs')
    expect(get).toHaveBeenNthCalledWith(2, '/admin/judge-reports')
  })
})
