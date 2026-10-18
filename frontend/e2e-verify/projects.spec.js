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
