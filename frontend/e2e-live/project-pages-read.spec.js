import { test, expect } from '@playwright/test'

test('现有项目五个页面读取真实数据，刷新后保持在线', async ({ page, request }) => {
  const apiBase = process.env.AICAP_READ_API_BASE || 'http://127.0.0.1:8080'
  const login = await request.post(`${apiBase}/api/auth/login`, { data: { username: '成员1', password: '123456' } })
  expect(login.ok()).toBeTruthy()
  const { access_token: token } = await login.json()
  const headers = { Authorization: `Bearer ${token}` }
  const projectsResponse = await request.get(`${apiBase}/api/projects`, { headers })
  const projects = await projectsResponse.json()
  const project = projects.find(row => row.story_count > 0 && row.task_count > 0)
  expect(project).toBeTruthy()
  const data = {}
  for (const endpoint of ['stories', 'tasks', 'pool', 'stories/logs']) {
    const response = await request.get(`${apiBase}/api/${endpoint}?projectId=${project.id}`, { headers })
    expect(response.status(), endpoint).toBe(200)
    data[endpoint] = await response.json()
  }
  await page.addInitScript(({ token, projectId }) => {
    localStorage.setItem('aiguanli_token', token)
    localStorage.setItem('aiguanli-current-project', projectId)
  }, { token, projectId: project.id })
  const failures = []
  page.on('response', response => {
    if (new URL(response.url()).pathname.startsWith('/api/') && response.status() >= 500) failures.push(`${response.status()} ${new URL(response.url()).pathname}`)
  })
  await page.goto(`/#/projects/${project.id}/overview`)
  await expect.poll(() => page.evaluate(() => window.stories.length)).toBe(data.stories.length)
  await expect(page.locator('#mode-chip')).toContainText('在线 · 已连接后端')
  await expect(page.locator('#login')).not.toBeVisible()
  for (const view of ['pool', 'board', 'gantt', 'members']) {
    await page.locator(`.navitem[data-view="${view}"]`).click()
    await expect(page.locator(`#view-${view}`)).toBeVisible()
    expect(await page.evaluate(() => window.stories.length)).toBe(data.stories.length)
    expect(await page.evaluate(() => window.tasks.length)).toBe(data.tasks.length)
    if (view === 'pool') await expect(page.locator('#pool-list .pool-item')).toHaveCount(data.pool.length)
    if (view === 'board') await expect(page.locator('#hintcount')).toContainText(`${data.stories.length} 条真实故事`)
    if (view === 'gantt') await expect(page.locator('#view-gantt')).toContainText(data.tasks[0].name)
    if (view === 'members') await expect(page.locator('#member-grid [data-member]')).toHaveCount(5)
  }
  await page.reload()
  await expect.poll(() => page.evaluate(() => window.tasks.length)).toBe(data.tasks.length)
  await expect(page.locator('#user-chip')).toBeVisible()
  expect(failures).toEqual([])
  console.log(`真实接口验证：${data.stories.length} 条故事、${data.tasks.length} 条任务、${data.pool.length} 条需求，五页读取正常。`)
})
