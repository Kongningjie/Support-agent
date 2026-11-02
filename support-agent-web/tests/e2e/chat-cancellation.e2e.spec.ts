import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { loginFromPage } from './support/browser-actions'

/** 仅使用专用本地后端测试进程的合成账号，不打印或写入浏览器产物。 */
function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少 ${name}，请按聊天断连验收说明启动隔离测试后端`)
  return value
}

test('真实取消、忙恢复及已提交结果重放，不自动重试、不重复正文', async ({ page, request }) => {
  const unresolvedComponents: string[] = []
  page.on('console', (message) => {
    if (message.text().includes('Failed to resolve component:'))
      unresolvedComponents.push(message.text())
  })
  const username = required('E2E_ADMIN_USERNAME')
  const password = required('E2E_ADMIN_PASSWORD')
  const login = await request.post('/api/v1/auth/login', { data: { username, password } })
  expect(login.ok()).toBe(true)
  const token = ((await login.json()) as { data: { accessToken: string } }).data.accessToken
  const headers = { Authorization: `Bearer ${token}` }
  const seed = await request.post('/api/v1/chat/stream', {
    headers,
    data: {
      spaceId: '00000000-0000-0000-0000-000000000001',
      clientMessageId: crypto.randomUUID(),
      message: '告诉我今天的股票行情',
    },
  })
  const match = (await seed.text()).match(/"conversationId":"([0-9a-f-]{36})"/)
  if (!match?.[1]) throw new Error('初始化会话失败')
  const id = match[1]
  const requests: Array<{
    message: string
    clientMessageId: string
    expectedConversationVersion: number
  }> = []
  page.on('request', (req) => {
    if (req.url().endsWith('/chat/stream') && req.method() === 'POST')
      requests.push(req.postDataJSON() as (typeof requests)[number])
  })
  try {
    await loginFromPage(page, username, password)
    await expect(page).toHaveURL(/\/chat$/)
    await page.goto(`/conversations/${id}`)
    await page.getByLabel('问题描述').fill('你好')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByText('正在识别问题意图', { exact: true })).toBeVisible()
    await expect
      .poll(async () => Number(await readFile(required('E2E_CHAT_MODEL_STATE'), 'utf8')))
      .toBe(1)
    // 读取服务端 RUNNING 事实后再停止浏览器接收；不拦截或伪造响应。
    await expect
      .poll(async () => {
        const res = await request.get(`/api/v1/conversations/${id}`, { headers })
        return ((await res.json()) as { data: { conversation: { status: string } } }).data
          .conversation.status
      })
      .toBe('RUNNING')
    await page.getByRole('button', { name: '取消本轮' }).click()
    await expect(page.getByText('连接已中断，结果状态待确认；可重新加载会话或重试')).toBeVisible()
    await page.getByRole('button', { name: '重试同一条消息' }).click()
    await expect(page.getByText('该会话正在处理另一条消息', { exact: true })).toBeVisible()
    expect(requests).toHaveLength(2)
    expect(requests[1]?.clientMessageId).toBe(requests[0]?.clientMessageId)
    await expect
      .poll(
        async () => {
          const res = await request.get(`/api/v1/conversations/${id}`, { headers })
          return ((await res.json()) as { data: { conversation: { status: string } } }).data
            .conversation.status
        },
        // Vite 代理的 TCP 缓冲可能到第二次心跳才暴露断连，冻结方案未承诺 15 秒探测 SLA。
        { timeout: 35_000 },
      )
      .toBe('IDLE')
    expect(requests).toHaveLength(2)
    await page.getByRole('button', { name: '重试同一条消息' }).click()
    await expect(page.getByText('你好，请描述需要处理的技术问题。', { exact: true })).toBeVisible()
    expect(requests[2]?.clientMessageId).toBe(requests[0]?.clientMessageId)

    await page.getByLabel('问题描述').fill('谢谢')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect
      .poll(async () => {
        const res = await request.get(`/api/v1/conversations/${id}`, { headers })
        return ((await res.json()) as { data: { conversation: { version: number } } }).data
          .conversation.version
      })
      .toBe(3)
    await page.getByRole('button', { name: '取消本轮' }).click()
    await expect(page.getByText('连接已中断，结果状态待确认；可重新加载会话或重试')).toBeVisible()
    await page.getByRole('button', { name: '重试同一条消息' }).click()
    await expect(page.locator('.chat-turn')).toHaveCount(3)
    await expect(page.getByText('你好，请描述需要处理的技术问题。', { exact: true })).toHaveCount(2)
    await expect(page.getByRole('button', { name: '评价第 3 条回答' })).toBeVisible()
    expect(requests[4]?.clientMessageId).toBe(requests[3]?.clientMessageId)
    expect(requests[4]?.expectedConversationVersion).toBe(2)
    const details = await request.get(`/api/v1/conversations/${id}`, { headers })
    const saved = (await details.json()) as {
      data: { conversation: { version: number }; recentTurns: unknown[] }
    }
    expect(saved.data.conversation.version).toBe(3)
    expect(saved.data.recentTurns).toHaveLength(3)
    expect(unresolvedComponents).toEqual([])
  } finally {
    // 测试超时会关闭请求上下文，清理不能覆盖原失败；临时容器仍由联调进程回收。
    const res = await request.get(`/api/v1/conversations/${id}`, { headers }).catch(() => null)
    if (res?.ok()) {
      const details = (await res.json()) as {
        data: { conversation: { version: number; status: string } }
      }
      if (details.data.conversation.status === 'IDLE') {
        const deleted = await request.delete(`/api/v1/conversations/${id}`, {
          headers,
          params: { expectedVersion: details.data.conversation.version },
        })
        expect(deleted.ok()).toBe(true)
      }
    }
  }
})
