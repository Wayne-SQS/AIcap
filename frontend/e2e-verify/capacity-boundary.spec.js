import { test, expect } from '@playwright/test'

test('项目周容量 0 保持为 0，带工时任务显示超载，清空后恢复默认值', async ({ page }) => {
  let weeklyCapacity = 0
  const writes = []
  const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'test-token')
    localStorage.setItem('aiguanli-current-project', 'P003')
  })
  await page.route('http://127.0.0.1:8080/api/**', route => route.fulfill(json([])))
  await page.route('http://127.0.0.1:8080/api/health', route => route.fulfill(json({})))
  await page.route('http://127.0.0.1:8080/api/auth/me', route => route.fulfill(json({ id: 1, role: 'admin', display_name: '负责人' })))
  await page.route('http://127.0.0.1:8080/api/projects', route => route.fulfill(json([
    { id: 'P003', name: '容量测试项目', project_role: 'owner', can_write: true, can_manage: true, total_sprints: 3 }
  ])))
  await page.route('http://127.0.0.1:8080/api/auth/users', route => route.fulfill(json([
    { id: 1, role: 'admin', display_name: '负责人', capacity_hours: 60 }
  ])))
  await page.route('http://127.0.0.1:8080/api/projects/P003/members', route => route.fulfill(json([
    { user_id: 1, project_role: 'owner', capacity_hours: 60, weekly_capacity_hours: weeklyCapacity }
  ])))
  await page.route('http://127.0.0.1:8080/api/projects/P003/members/1', async route => {
    expect(route.request().method()).toBe('PUT')
    const body = route.request().postDataJSON()
    writes.push(body)
    weeklyCapacity = body.weekly_capacity_hours
    await route.fulfill(json({ user_id: 1, project_role: 'owner', weekly_capacity_hours: weeklyCapacity }))
  })
  await page.route('http://127.0.0.1:8080/api/tasks*', route => route.fulfill(json([
    { id: 'T01', name: '待完成任务', owner_id: 1, hours: 4, week_start: 1, week_end: 1, status: 0 }
  ])))

  await page.goto('/#/projects/P003/members')
  const bandwidth = page.locator('#bandwidth .mcard').filter({ has: page.getByRole('heading', { name: '负责人' }) })
  await expect(bandwidth.locator('.role')).toHaveAttribute('title', /每周 0h/)
  await expect(bandwidth.locator('.loadflag')).toHaveText('超载')
  await expect(page.locator('#heat .heat-row').first().locator('.heat-cell').first()).toHaveAttribute('title', /周容量：0\.0h[\s\S]*负载等级：超载/)

  await bandwidth.getByRole('button', { name: '设置周容量' }).click()
  await bandwidth.getByRole('spinbutton', { name: '人时/周' }).fill('')
  await bandwidth.getByRole('button', { name: '保存' }).click()
  await expect(bandwidth.locator('.role')).toHaveAttribute('title', /每周 15h/)
  expect(writes).toEqual([{ member_role: 'owner', weekly_capacity_hours: null }])

  await bandwidth.getByRole('button', { name: '设置周容量' }).click()
  await bandwidth.getByRole('spinbutton', { name: '人时/周' }).fill('0')
  await bandwidth.getByRole('button', { name: '保存' }).click()
  await expect(bandwidth.locator('.role')).toHaveAttribute('title', /每周 0h/)
  await expect(bandwidth.locator('.loadflag')).toHaveText('超载')
  expect(writes.at(-1)).toEqual({ member_role: 'owner', weekly_capacity_hours: 0 })
})
