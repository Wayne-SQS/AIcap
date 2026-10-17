import { test, expect } from '@playwright/test'

const projectId = 'P002'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'

test('story map Sprint move persists active child schedule through Java', async ({ browser, request }) => {
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const created = await request.post(`${javaBase}/api/stories?projectId=${projectId}`, {
    headers, data: { title: 'Sprint2故事地图级联验收', description: '跨Sprint同步活动任务',
      role: '项目成员', goal: '维持故事与任务排期一致', acceptance: '刷新后活动任务排期在Sprint2',
      priority: 'Should', sprint: 1, activity: 2, status: 0, owner_id: 1 }
  })
  expect(created.status()).toBe(200)
  const storyId = (await created.json()).id
  const context = await browser.newContext()
  const page = await context.newPage()
  try {
    const linked = await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers, data: { kanban_card_id: storyId, story_ref: storyId, week_start: 1, week_end: 1, status: 0 }
    })
    expect(linked.status()).toBe(200)
    await page.goto('/#/projects')
    await page.locator('#login-user').fill('李锐铭')
    await page.locator('#login-pass').fill('123456')
    await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('#login')).not.toBeVisible()
    await page.getByRole('button', { name: '进入项目' }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
    await page.getByRole('link', { name: '用户故事看板' }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/board`))
    const card = page.locator(`#map .card[data-id="${storyId}"]`)
    await expect(card).toBeVisible()
    await card.dragTo(page.locator('[data-map-drop="2-2"]'))
    await expect(page.locator('.risk-dialog')).toContainText('未完成子任务随动')
    await page.locator('.risk-dialog').getByRole('button', { name: '仍然保存' }).click()
    await expect(page.locator(`[data-map-drop="2-2"] .card[data-id="${storyId}"]`)).toBeVisible()
    const readTask = async () => {
      const response = await request.get(`${javaBase}/api/tasks?projectId=${projectId}`, { headers })
      expect(response.status()).toBe(200)
      return (await response.json()).find(row => row.id === 'T998')
    }
    await expect.poll(async () => (await readTask()).week_start).toBe(3)
    expect(await readTask()).toMatchObject({ week_start: 3, week_end: 3 })
    await page.reload()
    await expect(page.locator(`[data-map-drop="2-2"] .card[data-id="${storyId}"]`)).toBeVisible()
    await page.goto(`/#/projects/${projectId}/gantt`)
    await expect(page.locator('[data-gantt-task="task:T998"]')).toBeVisible()
  } finally {
    await context.close()
    await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers, data: { kanban_card_id: null, story_ref: '', owner_id: 1, status: 0, week_start: 1, week_end: 1 }
    })
    const removed = await request.delete(`${javaBase}/api/stories/${storyId}?projectId=${projectId}&undone=keep`, { headers })
    expect(removed.status()).toBe(200)
  }
})
