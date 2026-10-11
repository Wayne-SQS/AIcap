import { randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

const webBase = process.env.AICAP_LIVE_WEB_BASE || 'http://127.0.0.1:15173'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'

test('cursor pagination reveals older pending proposals without losing any row', async ({ page, request }) => {
  const login = await request.post(javaBase + '/api/auth/login', {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const transcript = '审核分页测试：每条建议均需人工审核。'
  const meeting = await request.post(javaBase + '/api/meetings', {
    headers, data: { title: `审核分页测试 ${randomUUID().slice(0, 8)}`, transcript }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  const ids = []
  for (let index = 0; index < 51; index++) {
    const suggestion = await request.post(javaBase + '/api/suggestions', {
      headers, data: {
        meeting_id: meetingId, client_request_id: randomUUID(), action: 'pool.create', origin: 'manual',
        evidence: '每条建议均需人工审核', note: `分页测试建议 ${index + 1}`,
        changes: { title: `分页测试需求 ${index + 1}`, description: '验证较早的待审核提案仍可翻页找到', priority: 'Should' }
      }
    })
    expect(suggestion.status()).toBe(200)
    ids.push((await suggestion.json()).id)
  }

  await page.goto(webBase + '/#/review')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  const firstPage = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/review-queue' && response.request().method() === 'GET')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  const first = await firstPage
  expect(first.status()).toBe(200)
  const payload = await first.json()
  expect(payload.items).toHaveLength(50)
  expect(payload.nextCursor).toBeTruthy()
  const queue = page.getByRole('region', { name: '跨流程提案队列' })
  await expect(queue.locator('[data-queue-row]')).toHaveCount(50)
  const nextPage = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/review-queue' && url.searchParams.has('cursor')
  })
  await queue.getByRole('button', { name: '加载更多提案' }).click()
  expect((await nextPage).status()).toBe(200)
  await expect(queue.getByRole('button', { name: '加载更多提案' })).toHaveCount(0)
  for (const id of ids) await expect(queue.locator(`[data-meeting-suggestion="${id}"]`)).toHaveCount(1)

  const summaryResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/review-queue/summary' && response.request().method() === 'GET')
  await page.goto(webBase + '/#/ai')
  const summary = await summaryResponse
  expect(summary.status()).toBe(200)
  const counts = await summary.json()
  expect(counts.pendingCount).toBeGreaterThanOrEqual(51)
  await expect(page.getByText(`${counts.pendingCount} 条待审核`)).toBeVisible()
  await expect(page.getByText(`${counts.executionCount} 条已批准待执行`, { exact: false })).toBeVisible()
})
