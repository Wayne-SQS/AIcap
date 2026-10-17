import { test, expect } from '@playwright/test'

test('仅属于新项目的成员可进入项目页且不能打开默认项目页面', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'test-token'))
  await page.route('**/api/health', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
  await page.route('**/api/auth/me', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 3, display_name: '测试成员', role: 'member', can_write: true }) }))
  await page.route('**/api/projects', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 2, name: '隔离项目', description: '测试项目', member_role: 'member' }]) }))
  await page.route('**/api/projects/2/members', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 3, display_name: '测试成员', member_role: 'member' }]) }))
  for (const path of ['stories', 'stories/logs', 'tasks', 'pool', 'milestones']) {
    await page.route(`**/api/${path}`, route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  }
  await page.route('**/api/epics', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route('**/api/auth/users', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 3, display_name: '测试成员', role: 'member', capacity_hours: 10 }]) }))
  await page.route('**/api/dashboard', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ total: 0, done: 0, doing: 0, todo: 0, percent: 0, by_sprint: [], last_updated_at: null }) }))

  await page.goto('/#/overview')
  await expect(page.getByText('测试成员 · 成员')).toBeVisible()
  await expect(page.getByRole('heading', { name: '隔离项目' })).toBeVisible()
  await expect(page.locator('#ov-total')).toHaveText('00')
  await expect(page.locator('#ov-tasks')).toHaveText('0')
  await expect(page.locator('#realtime')).toContainText('0%')
  await expect(page.getByText('暂无故事数据，可先创建用户故事。')).toBeVisible()
  await expect(page.getByText('暂无更新')).toBeVisible()
  await expect(page.getByText('暂无里程碑。')).toBeVisible()
  await page.getByRole('link', { name: '项目与成员' }).click()
  await expect(page.getByRole('heading', { name: '项目与成员' })).toBeVisible()
  await page.getByRole('button', { name: /隔离项目/ }).click()
  await expect(page.getByText('测试成员', { exact: false }).last()).toBeVisible()
  await page.getByRole('button', { name: '进入项目工作区' }).click()
  await expect(page).toHaveURL(/#\/overview$/)
  await page.goto('/#/profiles')
  await expect(page).toHaveURL(/#\/projects$/)
})
