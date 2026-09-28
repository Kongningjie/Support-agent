import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
const target = new URL(baseURL)
if (!['localhost', '127.0.0.1', '[::1]', '::1'].includes(target.hostname)) {
  throw new Error('E2E_BASE_URL 只允许指向本机 dev/test 环境')
}

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: 'test-results',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL,
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1',
    url: baseURL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
})
