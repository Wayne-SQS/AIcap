import { randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

const webBase = process.env.AICAP_LIVE_WEB_BASE || 'http://127.0.0.1:15173'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'

test('real review queue keeps source navigation and role permissions', async ({ browser, request }) => {
  const login = await request.post(javaBase + '/api/auth/login', {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const transcript = '角色矩阵测试：先人工核对会议建议，再决定是否采纳。'
  const meeting = await request.post(javaBase + '/api/meetings', {
    headers, data: { title: `角色矩阵测试 ${randomUUID().slice(0, 8)}`, transcript }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  const suggestion = await request.post(javaBase + '/api/suggestions', {
    headers, data: {
      meeting_id: meetingId, client_request_id: randomUUID(), action: 'pool.create', origin: 'manual',
      evidence: '先人工核对会议建议', note: '确认角色权限后处理',
      changes: { title: '角色矩阵测试建议', description: '验证跨流程审核队列', priority: 'Should' }
    }
  })
  expect(suggestion.status()).toBe(200)
  const suggestionId = (await suggestion.json()).id

  const accounts = [
    { username: '李锐铭', canReview: true },
    { username: '孙秋实', canReview: false },
    { username: '成员5', canReview: false },
    { username: '高思晗', canReview: true, decide: true }
  ]

  for (const account of accounts) {
    const context = await browser.newContext({ baseURL: webBase })
    const page = await context.newPage()
    try {
      await page.goto('/#/review')
      await page.locator('#login-user').fill(account.username)
      await page.locator('#login-pass').fill('123456')
      await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
      await expect(page.locator('#login')).not.toBeVisible()

      const queue = page.getByRole('region', { name: '跨流程提案队列' })
      const row = queue.locator(`[data-meeting-suggestion="${suggestionId}"]`)
      await expect(row).toBeVisible()
      await expect(row).toContainText('待审核')

      if (account.canReview) {
        await expect(queue.locator('.queue-permission')).toHaveCount(0)
        await expect(row.locator('.sug-acts')).toBeVisible()
      } else {
        await expect(queue.locator('.queue-permission')).toBeVisible()
        await expect(row.locator('.sug-acts')).toHaveCount(0)
      }

      await row.getByRole('button', { name: '查看来源会议' }).click()
      await expect(page.getByRole('heading', { name: '会议工作区' })).toBeVisible()
      if (account.username === '成员5') {
        await expect(page.locator('#meeting-save-form button')).toBeDisabled()
      } else {
        await expect(page.locator('#meeting-save-form button')).toBeEnabled()
      }
      await page.getByRole('button', { name: '← 返回审核中心（保留筛选）' }).click()
      await expect(page.getByLabel('处理状态')).toHaveValue('pending')
      await expect(row).toBeVisible()

      if (account.decide) {
        page.once('dialog', dialog => dialog.accept('角色矩阵测试：暂不采纳'))
        await row.getByRole('button', { name: '拒绝', exact: true }).click()
        await expect(row).toHaveCount(0)
      }
    } finally {
      await context.close()
    }
  }

  const saved = await request.get(javaBase + `/api/suggestions/${suggestionId}`, { headers })
  expect(saved.status()).toBe(200)
  expect(await saved.json()).toMatchObject({ status: 'rejected', pool_item_id: null })
})
