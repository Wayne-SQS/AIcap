import { randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'
const projectId = 'P002'
const dateText = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

test('Sprint 2 activity heatmap distinguishes a recorded day from a zero day', async ({ browser, request }) => {
  const now = new Date()
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (now.getDay() + 6) % 7)
  const friday = new Date(monday); friday.setDate(monday.getDate() - 3)
  const thursday = new Date(friday); thursday.setDate(friday.getDate() - 1)
  const activeDate = dateText(friday), zeroDate = dateText(thursday)
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const recorded = await request.post(`${javaBase}/api/profile-agent/activities?projectId=${projectId}`, {
    headers, data: { user_id: 1, activity_type: 'note', title: `Sprint2活动图在线验收 ${randomUUID().slice(0, 8)}`,
      detail: '独立测试库的活动来源与自然日核对', module: 'Sprint 2', happened_at: `${activeDate} 10:00:00` }
  })
  expect(recorded.status()).toBe(200)
  expect((await recorded.json()).source).toBe('manual')
  const heatmap = await request.get(`${javaBase}/api/profile-agent/heatmap?projectId=${projectId}&userId=1&start=${zeroDate}&end=${activeDate}`, { headers })
  expect(heatmap.status()).toBe(200)
  const days = await heatmap.json()
  expect(days.find(row => row.date === activeDate).total).toBeGreaterThan(0)
  expect(days.find(row => row.date === zeroDate).total).toBe(0)

  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    await page.goto('/#/projects')
    await page.locator('#login-user').fill('李锐铭')
    await page.locator('#login-pass').fill('123456')
    await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('#login')).not.toBeVisible()
    await page.getByRole('button', { name: '进入项目' }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
    await page.goto(`/#/projects/${projectId}/members`)
    const member = page.locator('#contrib .contrib').first()
    await expect(member).toContainText('真实活动')
    await expect(member.locator(`.c-cell[title*="${activeDate}"]`)).toHaveAttribute('title', /活动 1 次.*来源:活动记录/s)
    await expect(member.locator(`.c-cell[title*="${zeroDate}"]`)).toHaveAttribute('title', /无活动/)
  } finally { await context.close() }
})
