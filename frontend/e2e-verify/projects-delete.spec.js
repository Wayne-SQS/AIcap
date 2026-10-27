import { test, expect } from '@playwright/test'

async function openProjects(page, canManage) {
  let deletes = 0
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P003')
  })
  await page.route('**/api/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/projects/P003' && request.method() === 'DELETE') {
      deletes += 1
      return route.fulfill({ json: { deleted: true } })
    }
    let data = []
    if (path === '/api/health') data = { status: 'ok' }
    if (path === '/api/auth/me') data = { id: 3, role: 'member', display_name: '测试用户' }
    if (path === '/api/projects') data = [{ id: 'P003', name: '测试项目', description: '', status: 'ACTIVE', can_manage: canManage, can_write: canManage, story_count: 2, task_count: 1, member_count: 1 }]
    if (path === '/api/projects/P003/members') data = [{ user_id: 3, project_role: canManage ? 'owner' : 'viewer', weekly_capacity_hours: 10 }]
    return route.fulfill({ json: data })
  })
  await page.goto('/#/projects')
  await expect(page.getByRole('heading', { name: '测试项目' })).toBeVisible()
  await page.getByRole('button', { name: '项目成员' }).click()
  return () => deletes
}

test('负责人确认项目名称后删除并清除当前项目', async ({ page }) => {
  const deleteCount = await openProjects(page, true)
  await page.getByRole('button', { name: '删除项目', exact: true }).click()
  const confirm = page.getByRole('button', { name: '确认删除项目' })
  await expect(confirm).toBeDisabled()
  await page.getByLabel('输入项目名称「测试项目」以确认').fill('别的项目')
  await expect(confirm).toBeDisabled()
  await page.getByLabel('输入项目名称「测试项目」以确认').fill('测试项目')
  await confirm.click()
  await expect(page.getByRole('heading', { name: '测试项目' })).toHaveCount(0)
  expect(deleteCount()).toBe(1)
  expect(await page.evaluate(() => localStorage.getItem('aiguanli-current-project'))).toBeNull()
})

test('无管理权限的成员看不到删除入口', async ({ page }) => {
  const deleteCount = await openProjects(page, false)
  await expect(page.getByRole('button', { name: '删除项目' })).toHaveCount(0)
  expect(deleteCount()).toBe(0)
})
