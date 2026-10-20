import { test, expect } from '@playwright/test'

test('项目列表只显示有权限的项目，并从卡片进入项目工作区', async ({ page }) => {
  const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'test-token'))
  await page.route('**/api/health', route => route.fulfill(json({})))
  await page.route('**/api/auth/me', route => route.fulfill(json({ id: 3, display_name: '测试成员', role: 'member', can_write: true })))
  await page.route('**/api/projects', route => route.fulfill(json([
    { id: 'P003', name: '隔离项目', description: '测试项目', project_role: 'member', can_write: true,
      can_manage: false, story_count: 0, task_count: 0, member_count: 1 }
  ])))
  await page.route('**/api/stories/logs?*', route => route.fulfill(json([])))
  for (const path of ['stories', 'tasks', 'pool', 'milestones', 'epics'])
    await page.route(`**/api/${path}?*`, route => route.fulfill(json([])))
  await page.route('**/api/auth/users', route => route.fulfill(json([{ id: 3, display_name: '测试成员', role: 'member', capacity_hours: 60 }])))
  await page.route('**/api/projects/P003/members', route => route.fulfill(json([
    { user_id: 3, project_role: 'member', weekly_capacity_hours: 10 }
  ])))
  await page.route('**/api/dashboard?*', route => route.fulfill(json({ total: 0, done: 0, doing: 0, todo: 0, percent: 0, by_sprint: [] })))

  await page.goto('/#/projects')
  await expect(page.getByRole('heading', { name: '我的项目' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '隔离项目' })).toBeVisible()
  await expect(page.locator('.projects-card')).toHaveCount(1)
  await page.getByRole('button', { name: '进入项目' }).click()
  await expect(page).toHaveURL(/#\/projects\/P003\/overview$/)
})

test('项目入口收到空列表时显示空态，不误开登录弹窗', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'test-token'))
  await page.route('**/api/health', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
  await page.route('**/api/auth/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 3, role: 'member', display_name: '测试成员' }) }))
  await page.route('**/api/projects', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))

  await page.goto('/#/projects')
  await expect(page.getByText('当前账号没有可访问的项目。')).toBeVisible()
  await expect(page.locator('.projects-card')).toHaveCount(0)
  await expect(page.locator('#login')).not.toBeVisible()
})

test('项目列表故障不伪装成空数据或登录过期，重试后恢复', async ({ page }) => {
  let available = false
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'test-token'))
  await page.route('**/api/health', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
  await page.route('**/api/auth/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 3, role: 'member', display_name: '测试成员' }) }))
  await page.route('**/api/projects', route => route.fulfill({
    status: available ? 200 : 503, contentType: 'application/json',
    body: available ? '[]' : JSON.stringify({ detail: '服务暂不可用' })
  }))

  await page.goto('/#/projects')
  await expect(page.getByRole('alert')).toContainText('服务暂不可用')
  await expect(page.getByText('当前账号没有可访问的项目。')).toHaveCount(0)
  await expect(page.locator('#login')).not.toBeVisible()
  available = true
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByText('当前账号没有可访问的项目。')).toBeVisible()
})
