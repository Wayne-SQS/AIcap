import { test, expect } from '@playwright/test'

const projectId = 'P002'
const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'

async function enterProject(browser, username) {
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

test('Sprint 2 four views respect the selected project role for viewer and writer', async ({ browser, request }) => {
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const adminHeaders = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const created = await request.post(`${javaBase}/api/stories?projectId=${projectId}`, {
    headers: adminHeaders,
    data: { title: '四视图权限联调故事', description: '只读账号可查看故事但不可修改', role: '项目成员', goal: '查看权限',
      acceptance: '只读账号无法编辑', priority: 'Should', sprint: 2, activity: 2, status: 0, owner_id: 1 }
  })
  expect(created.status()).toBe(200)
  const storyId = (await created.json()).id

  try {
    const viewerLogin = await request.post(`${javaBase}/api/auth/login`, {
      data: { username: '成员5', password: '123456' }
    })
    expect(viewerLogin.status()).toBe(200)
    const viewerHeaders = { Authorization: `Bearer ${(await viewerLogin.json()).access_token}` }
    const forbidden = await request.patch(`${javaBase}/api/stories/${storyId}?projectId=${projectId}`, {
      headers: viewerHeaders, data: { title: '越权修改' }
    })
    expect(forbidden.status()).toBe(403)

    for (const { username, writable } of [
      { username: '成员5', writable: false },
      { username: '孙秋实', writable: true }
    ]) {
      const { context, page } = await enterProject(browser, username)
      try {
        await page.goto(`/#/projects/${projectId}/board`)
        const card = page.locator(`.card[data-id="${storyId}"]:visible`).first()
        await expect(card).toBeVisible()
        await expect(card).toHaveJSProperty('disabled', !writable)
        await expect(card).toHaveAttribute('draggable', String(writable))
        await expect(page.locator('#new')).toHaveJSProperty('disabled', !writable)

        await page.goto(`/#/projects/${projectId}/gantt`)
        await expect(page.getByRole('heading', { name: '甘特图' })).toBeVisible()
        // The isolated project has no tasks. The task-level drag/edit path needs a separate fixture.
        await expect(page.locator('[data-gantt-task]')).toHaveCount(0)

        await page.goto(`/#/projects/${projectId}/members`)
        await expect(page.locator('#member-grid .mcard')).toHaveCount(5)
        await expect(page.locator('.member-task-card')).toHaveCount(0)
        await expect(page.getByRole('button', { name: '设置周容量' })).toHaveCount(0)

        await page.goto(`/#/projects/${projectId}/uml`)
        const usecase = page.locator('.usecase-svg .uc-hit').first()
        await expect(usecase).toBeVisible()
        if (writable) {
          await expect(page.locator('.usecase-svg')).not.toHaveClass(/is-readonly/)
        } else {
          await expect(page.locator('.usecase-svg')).toHaveClass(/is-readonly/)
          await expect(page.getByText('当前项目为只读权限，布局不可调整。')).toBeVisible()
        }
        await page.locator('.usecase-svg .uc-hit').filter({ hasText: '配置角色与权限' }).click()
        await page.locator('.uc-detail').getByRole('button', { name: '查看故事时序' }).first().click()
        await expect(page.locator('.sequence-editor')).toHaveCount(writable ? 1 : 0)
      } finally { await context.close() }
    }

    const saved = await request.get(`${javaBase}/api/stories?projectId=${projectId}`, { headers: adminHeaders })
    expect(saved.status()).toBe(200)
    expect((await saved.json()).find(row => row.id === storyId).title).toBe('四视图权限联调故事')
  } finally {
    const removed = await request.delete(`${javaBase}/api/stories/${storyId}?projectId=${projectId}&undone=keep`, { headers: adminHeaders })
    expect(removed.status()).toBe(200)
  }
})
