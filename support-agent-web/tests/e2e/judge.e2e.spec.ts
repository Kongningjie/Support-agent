import { expect, test } from '@playwright/test'
import { loginFromPage } from './support/browser-actions'

test('真实后端：脱敏样本、Judge关闭提示和只读报告', async ({ page, request }) => {
  const username = process.env.E2E_ADMIN_USERNAME
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!username || !password) throw new Error('需要专用本地E2E管理员配置')
  const login = await request.post('/api/v1/auth/login', { data: { username, password } })
  expect(login.status()).toBe(200)
  const envelope = (await login.json()) as { data: { accessToken: string } }
  const headers = { Authorization: `Bearer ${envelope.data.accessToken}` }
  const source = await request.get('/api/v1/admin/human-evaluations/fixed-candidates', { headers })
  expect(source.status()).toBe(200)
  const candidates = (await source.json()) as { data: { caseId: string; question: string }[] }
  const existing = await request.get('/api/v1/admin/human-evaluations', { headers })
  expect(existing.status()).toBe(200)
  const rows = (await existing.json()) as { data: { id: string; sourceRef: string }[] }
  const fixed = candidates.data.find(
    (item) => !rows.data.some((row) => row.sourceRef === item.caseId),
  )
  if (!fixed) throw new Error('请使用隔离测试库，固定样本来源已被占用')
  const create = await request.post('/api/v1/admin/human-evaluations', {
    headers,
    data: {
      sourceType: 'FIXED',
      sourceRef: fixed.caseId,
      spaceId: '00000000-0000-0000-0000-000000000001',
      question: fixed.question,
      answer: 'e2e脱敏测试回答',
      evidence: '无',
      sanitizedConfirmed: true,
    },
  })
  expect(create.status()).toBe(200)

  await loginFromPage(page, username, password)
  await expect(page).toHaveURL(/\/chat$/)
  await page.goto('/admin/human-evaluations')
  await page
    .getByRole('row')
    .filter({ hasText: fixed.caseId })
    .getByRole('button', { name: '查看', exact: true })
    .click()
  const panel = page.getByRole('region', { name: '模型辅助评审' })
  await expect(panel).toBeVisible()
  await expect(panel.getByText('暂无模型评审运行。')).toBeVisible()
  await panel.getByRole('button', { name: '执行模型评审 / 重试原请求' }).click()
  await page.getByRole('button', { name: '确认执行', exact: true }).click()
  await expect(page.getByText('Judge 未开启或未配置评审模型', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '查看人工一致性报告' }).click()
  await expect(panel.getByText('暂无模型评审运行。')).toBeVisible()
  const report = await request.get('/api/v1/admin/judge-reports', { headers })
  expect(report.status()).toBe(200)
  expect((await report.json()) as { data: unknown[] }).toHaveProperty('data', [])
})
