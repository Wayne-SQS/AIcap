import { test, expect } from '@playwright/test'

test('online review queue reads server-filtered cursor pages', async ({ page }) => {
  await page.goto('/#/review')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  const firstPage = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/review-queue' && response.request().method() === 'GET')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  const response = await firstPage
  expect(response.status()).toBe(200)
  const payload = await response.json()
  expect(Array.isArray(payload.items)).toBe(true)
  expect(Object.hasOwn(payload, 'nextCursor')).toBe(true)
  const queue = page.getByRole('region', { name: '跨流程提案队列' })
  await expect(queue).toBeVisible()
  await expect(queue.locator('[data-queue-row]')).toHaveCount(payload.items.length)

  const filteredPage = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/review-queue' && url.searchParams.get('source') === 'daily'
  })
  await queue.getByLabel('来源流程').selectOption('daily')
  expect((await filteredPage).status()).toBe(200)
})
