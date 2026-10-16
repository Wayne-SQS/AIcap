import { test, expect } from '@playwright/test'

// T998 is a temporary row in the isolated aicap_java_test database. The QA
// SQL fixture inserts it before this test and removes it afterwards; no API fixture
// replaces GET /api/tasks in this scenario.
const projectId = 'P002'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'

async function loginPage(browser, username) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/#/projects')
  await page.locator('#login-user').fill(username)
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.getByRole('button', { name: '进入项目' }).click()
  await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
  return { context, page }
}

test('Sprint 2 persisted task is scoped and refreshes across board, Gantt, members and UML', async ({ browser, request }) => {
  const adminLogin = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(adminLogin.status()).toBe(200)
  const adminHeaders = { Authorization: `Bearer ${(await adminLogin.json()).access_token}` }
  const memberLogin = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '孙秋实', password: '123456' }
  })
  expect(memberLogin.status()).toBe(200)
  const memberHeaders = { Authorization: `Bearer ${(await memberLogin.json()).access_token}` }
  const viewerLogin = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '成员5', password: '123456' }
  })
  expect(viewerLogin.status()).toBe(200)
  const viewerHeaders = { Authorization: `Bearer ${(await viewerLogin.json()).access_token}` }

  const created = await request.post(`${javaBase}/api/stories?projectId=${projectId}`, {
    headers: adminHeaders,
    data: { title: '四视图持久化任务联调', description: '同一任务状态和负责人贯通四视图',
      role: '项目成员', goal: '查看一致进展', acceptance: '刷新后状态与负责人一致',
      priority: 'Should', sprint: 2, activity: 2, status: 0, owner_id: 1 }
  })
  expect(created.status()).toBe(200)
  const storyId = (await created.json()).id
  try {
    const linked = await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers: adminHeaders, data: { kanban_card_id: storyId, story_ref: storyId, week_start: 3, week_end: 3 }
    })
    expect(linked.status()).toBe(200)
    expect((await linked.json()).kanban_card_id).toBe(storyId)

    const denied = await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers: viewerHeaders, data: { owner_id: 2, status: 2 }
    })
    expect(denied.status()).toBe(403)
    const viewer = await loginPage(browser, '成员5')
    try {
      await viewer.page.goto(`/#/projects/${projectId}/gantt`)
      const bar = viewer.page.locator('[data-gantt-task="task:T998"]')
      await expect(bar).toBeVisible()
      await expect(bar).not.toHaveClass(/is-draggable/)
      await bar.click()
      await expect(viewer.page.locator('#gantt-detail').getByRole('button', { name: '编辑任务' })).toBeDisabled()
      await viewer.page.goto(`/#/projects/${projectId}/members`)
      await expect(viewer.page.locator('[data-member="0"] .member-task-card').filter({ hasText: 'T998' })).toBeVisible()
      await expect(viewer.page.locator('.member-task-card').filter({ hasText: 'T998' })).toHaveAttribute('draggable', 'false')
    } finally { await viewer.context.close() }

    const moved = await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers: memberHeaders, data: { owner_id: 2, status: 2 }
    })
    expect(moved.status()).toBe(200)
    const storyDone = await request.patch(`${javaBase}/api/stories/${storyId}?projectId=${projectId}`, {
      headers: memberHeaders, data: { status: 2 }
    })
    expect(storyDone.status()).toBe(200)

    const writer = await loginPage(browser, '孙秋实')
    try {
      await writer.page.goto(`/#/projects/${projectId}/board`)
      await writer.page.getByRole('button', { name: '▤ 详细' }).click()
      const card = writer.page.locator(`.card[data-id="${storyId}"]:visible`).first()
      await expect(card).toBeVisible()
      await expect(card.locator('.subprog')).toContainText('1/1 · 100%')

      await writer.page.goto(`/#/projects/${projectId}/gantt`)
      const bar = writer.page.locator('[data-gantt-task="task:T998"]')
      await expect(bar).toHaveClass(/task-done/)
      await bar.click()
      await expect(writer.page.locator('#gantt-detail')).toContainText('已完成 · 100%')

      await writer.page.goto(`/#/projects/${projectId}/members`)
      await expect(writer.page.locator('[data-member="1"] .member-task-card').filter({ hasText: 'T998' })).toBeVisible()
      await expect(writer.page.locator('[data-member="0"] .member-task-card').filter({ hasText: 'T998' })).toHaveCount(0)

      await writer.page.goto(`/#/projects/${projectId}/uml`)
      await writer.page.locator('.usecase-svg .uc-hit').filter({ hasText: '关联 1 条故事' }).first().click()
      await expect(writer.page.locator('.uc-detail')).toContainText('四视图持久化任务联调')
    } finally { await writer.context.close() }

    const tasks = await request.get(`${javaBase}/api/tasks?projectId=${projectId}`, { headers: adminHeaders })
    expect(tasks.status()).toBe(200)
    expect((await tasks.json()).find(row => row.id === 'T998')).toMatchObject({ owner_id: 2, status: 2, progress: 100 })
  } finally {
    await request.patch(`${javaBase}/api/tasks/T998?projectId=${projectId}`, {
      headers: adminHeaders,
      data: { kanban_card_id: null, story_ref: '', owner_id: 1, status: 0, week_start: 1, week_end: 1 }
    })
    const removed = await request.delete(`${javaBase}/api/stories/${storyId}?projectId=${projectId}&undone=keep`, { headers: adminHeaders })
    expect(removed.status()).toBe(200)
  }
})
