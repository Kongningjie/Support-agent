import { expect, test } from '@playwright/test'
import { changedPassword, prepareUser, readE2eCredentials } from './support/e2e-environment'
import {
  expectNoHorizontalOverflow,
  loginFromPage,
  selectFirstSpace,
} from './support/browser-actions'

const credentials = readE2eCredentials()
const userPasswordAfterChange = changedPassword(credentials.userPassword)

test.describe.serial('真实本地后端核心链路', () => {
  test.beforeAll(async ({ request }) => {
    await prepareUser(request, credentials)
  })

  test.afterAll(async ({ request }) => {
    await prepareUser(request, credentials)
  })

  test('登录、强制改密、角色边界和窄屏键盘操作', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1024, height: 900 })
    await loginFromPage(page, credentials.userUsername, credentials.userPassword)
    await expect(page).toHaveURL(/\/change-password$/)
    await page.getByLabel('当前密码').fill(credentials.userPassword)
    await page.getByLabel('新密码', { exact: true }).fill(userPasswordAfterChange)
    await page.getByLabel('确认新密码').fill(userPasswordAfterChange)
    await page.getByRole('button', { name: '确认修改' }).click()
    await expect(page).toHaveURL(/\/login$/)

    await loginFromPage(page, credentials.userUsername, userPasswordAfterChange)
    await expect(page).toHaveURL(/\/chat$/)
    await page.goto('/admin/knowledge')
    await expect(page).toHaveURL(/\/forbidden$/)
    await page.goto('/route-that-does-not-exist')
    await expect(page.getByRole('heading', { name: '页面不存在' })).toBeVisible()
    await page.goto('/chat')
    const skipLink = page.getByRole('link', { name: '跳到主要内容' })
    await skipLink.focus()
    await expect(skipLink).toBeFocused()
    await skipLink.press('Enter')
    await expect(page.locator('#main-content')).toBeFocused()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({
      path: testInfo.outputPath('user-chat-1024.png'),
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('长期记忆设置与手工工单生命周期', async ({ page }) => {
    await loginFromPage(page, credentials.userUsername, userPasswordAfterChange)
    await page.getByRole('link', { name: '长期记忆' }).click()
    await expect(page.getByRole('heading', { name: '长期记忆' })).toBeVisible()
    const setting = page.locator('.memory-setting')
    const status = setting.getByText(/^长期记忆已(?:启用|关闭)$/)
    await expect(status).toBeVisible()
    const enabled = (await status.textContent()) === '长期记忆已启用'
    await page.getByRole('button', { name: enabled ? '关闭' : '启用', exact: true }).click()
    await expect(setting.getByText(enabled ? '长期记忆已关闭' : '长期记忆已启用')).toBeVisible()
    await page.getByRole('button', { name: enabled ? '启用' : '关闭', exact: true }).click()
    await expect(setting.getByText(enabled ? '长期记忆已启用' : '长期记忆已关闭')).toBeVisible()

    await page.getByRole('link', { name: '支持工单' }).click()
    await page.getByRole('button', { name: '手工创建草稿' }).click()
    await selectFirstSpace(page, '#ticket-create-space')
    const marker = `e2e-${Date.now()}`
    await page.getByLabel('工单标题（必填，1～160 字符）').fill(`${marker} 手工验收工单`)
    await page.getByLabel('问题描述（必填，1～8000 字符）').fill('验证真实前端建单与关闭链路。')
    await page.getByRole('button', { name: '创建草稿', exact: true }).click()
    await expect(page).toHaveURL(/\/tickets\/T\d{12}$/)
    await page.getByRole('button', { name: '关闭工单' }).click()
    await page.getByLabel('人工关闭原因（必填，1～500 字符）').fill('E2E 验收完成后清理。')
    await page.getByRole('button', { name: '确认关闭' }).click()
    await expect(page.getByText('已关闭', { exact: true }).first()).toBeVisible()
  })

  test('AI 对话、无知识建议建单和会话清理', async ({ page }, testInfo) => {
    await loginFromPage(page, credentials.userUsername, userPasswordAfterChange)
    await selectFirstSpace(page, '#chat-space')
    const marker = `E2E_NO_KNOWLEDGE_${Date.now()}`
    await page.getByLabel('问题描述').fill(`${marker} 专用中间件返回未知错误，应该如何修复？`)
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page).toHaveURL(/\/conversations\/[0-9a-f-]+$/, { timeout: 60_000 })
    const conversationUrl = page.url()
    const suggestionButton = page.getByRole('button', { name: '创建工单草稿' })
    await expect(suggestionButton).toBeVisible({ timeout: 60_000 })
    await suggestionButton.scrollIntoViewIfNeeded()
    await page.screenshot({
      path: testInfo.outputPath('chat-suggestion.png'),
      animations: 'disabled',
    })
    await page.getByRole('button', { name: '创建工单草稿' }).click()
    await expect(page).toHaveURL(/\/tickets\/T\d{12}$/)
    await page.getByRole('button', { name: '关闭工单' }).click()
    await page.getByLabel('人工关闭原因（必填，1～500 字符）').fill('E2E 建议工单验收完成。')
    await page.getByRole('button', { name: '确认关闭' }).click()
    await page.goto(conversationUrl)
    await expect(page.getByRole('heading', { name: '继续技术支持会话' })).toBeVisible()
    await page.getByRole('button', { name: '删除' }).click()
    await page.getByRole('button', { name: '永久删除' }).click()
    await expect(page).toHaveURL(/\/chat$/)
  })

  test('管理员知识草稿治理与验收截图', async ({ page }, testInfo) => {
    await loginFromPage(page, credentials.adminUsername, credentials.adminPassword)
    await page.getByRole('link', { name: '知识治理' }).click()
    await expect(page.getByRole('heading', { name: '知识治理' })).toBeVisible()
    await page.getByRole('button', { name: '导入知识' }).click()
    await selectFirstSpace(page, '#knowledge-create-space')
    const marker = `e2e-${Date.now()}`
    await page.getByLabel('标题（必填）').fill(`${marker} 临时知识草稿`)
    await page.getByLabel('正文（最大 1 MiB）').fill('仅用于 F5 真实本地联调，验收后立即删除。')
    await page.getByRole('button', { name: '创建草稿', exact: true }).click()
    await expect(page.getByRole('button', { name: '删除草稿' })).toBeVisible()
    await page.screenshot({
      path: testInfo.outputPath('admin-knowledge-draft.png'),
      fullPage: true,
      animations: 'disabled',
    })
    await page.getByRole('button', { name: '删除草稿' }).click()
    await page.getByRole('button', { name: '确认' }).click()
    await expect(page.getByText('知识草稿已删除')).toBeVisible()
  })
})
