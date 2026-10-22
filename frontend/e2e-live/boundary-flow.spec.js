import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'
const evidenceDir = process.env.AICAP_LIVE_ARTIFACT_DIR

async function login(request, username) {
  const response = await request.post(`${javaBase}/api/auth/login`, {
    data: { username, password: '123456' }
  })
  expect(response.status()).toBe(200)
  return (await response.json()).access_token
}

async function authenticatedPage(page, token, route = '/#/projects') {
  await page.addInitScript(value => localStorage.setItem('aiguanli_token', value), token)
  await page.goto(route)
  await expect(page.locator('#login')).not.toBeVisible()
}

test('real service: project entry shows a truly empty accessible list', async ({ page, request }) => {
  const token = await login(request, '边界访客')
  const headers = { Authorization: `Bearer ${token}` }
  const projects = await request.get(`${javaBase}/api/projects`, { headers })
  expect(projects.status()).toBe(200)
  expect(await projects.json()).toEqual([])

  await authenticatedPage(page, token)
  await expect(page.getByText('当前账号没有可访问的项目。')).toBeVisible()
  await expect(page.locator('.projects-card')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '我的项目' })).toBeVisible()
  await page.waitForTimeout(500)
  if (evidenceDir) await page.screenshot({ path: join(evidenceDir, 'empty-projects.png'), fullPage: true })
})

test('real service: zero capacity persists, overloads positive work and stays project scoped', async ({ page, request }) => {
  const token = await login(request, '李锐铭')
  const headers = { Authorization: `Bearer ${token}` }
  const post = async (path, data) => {
    const response = await request.post(`${javaBase}${path}`, { headers, data })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const put = async (path, data) => {
    const response = await request.put(`${javaBase}${path}`, { headers, data })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const p3 = await post('/api/projects', { name: '边界反例 P003', description: '隔离联调证据', total_sprints: 2 })
  const p4 = await post('/api/projects', { name: '边界对照 P004', description: '项目隔离对照', total_sprints: 2 })
  expect([p3.id, p4.id]).toEqual(['P003', 'P004'])

  const zero = await put(`/api/projects/${p3.id}/members/1`, { member_role: 'owner', weekly_capacity_hours: 0 })
  expect(zero.weekly_capacity_hours).toBe(0)
  await post(`/api/tasks?projectId=${p3.id}`, {
    name: '零容量反例任务', owner_id: 1, hours: 4, week_start: 1, week_end: 1
  })
  const member3 = await request.get(`${javaBase}/api/projects/${p3.id}/members`, { headers })
  const member4 = await request.get(`${javaBase}/api/projects/${p4.id}/members`, { headers })
  expect((await member3.json()).find(row => row.user_id === 1).weekly_capacity_hours).toBe(0)
  expect((await member4.json()).find(row => row.user_id === 1).weekly_capacity_hours).toBe(15)
  const tasks4 = await request.get(`${javaBase}/api/tasks?projectId=${p4.id}`, { headers })
  expect(await tasks4.json()).toEqual([])

  await authenticatedPage(page, token, `/#/projects/${p3.id}/members`)
  const bandwidth = page.locator('#bandwidth .mcard').filter({ has: page.getByRole('heading', { name: '李锐铭' }) })
  await expect(bandwidth.locator('.role')).toHaveAttribute('title', /每周 0h/)
  await expect(bandwidth.locator('.loadflag')).toHaveText('超载')
  await expect(page.locator('#heat .heat-row').first().locator('.heat-cell').first()).toHaveAttribute('title', /周容量：0\.0h[\s\S]*负载等级：超载/)
  await page.reload()
  await expect(bandwidth.locator('.loadflag')).toHaveText('超载')
  if (evidenceDir) await page.screenshot({ path: join(evidenceDir, 'zero-capacity.png'), fullPage: true })

  await bandwidth.getByRole('button', { name: '设置周容量' }).click()
  await bandwidth.getByRole('spinbutton', { name: '人时/周' }).fill('')
  await bandwidth.getByRole('button', { name: '保存' }).click()
  await expect(bandwidth.locator('.role')).toHaveAttribute('title', /每周 15h/)
  const reset = await request.get(`${javaBase}/api/projects/${p3.id}/members`, { headers })
  expect((await reset.json()).find(row => row.user_id === 1).weekly_capacity_hours).toBe(15)
  await bandwidth.getByRole('button', { name: '设置周容量' }).click()
  await bandwidth.getByRole('spinbutton', { name: '人时/周' }).fill('0')
  await bandwidth.getByRole('button', { name: '保存' }).click()
  await expect(bandwidth.locator('.loadflag')).toHaveText('超载')
})
