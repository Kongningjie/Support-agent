import { expect, type Page } from '@playwright/test'

/** 使用真实登录页建立浏览器会话。 */
export async function loginFromPage(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

/** 选择业务表单中的第一个真实知识空间，跳过空占位项。 */
export async function selectFirstSpace(page: Page, selector: string): Promise<void> {
  const select = page.locator(selector)
  await expect(select.locator('option')).not.toHaveCount(1)
  await select.selectOption({ index: 1 })
}

/** 断言 1024px 验收宽度下页面根节点不存在横向溢出。 */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth)
}
