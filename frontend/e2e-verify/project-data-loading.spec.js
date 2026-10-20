import { test, expect } from '@playwright/test'

test('审计接口故障不清空项目数据或登录态，并可重新加载恢复', async ({ page }) => {
  let logFailed = true
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'project-loading-test')
    localStorage.setItem('aiguanli-current-project', 'P002')
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/stories/logs' && logFailed) {
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ detail: '审计服务异常' }) })
    }
    const responses = {
      '/api/health': {},
      '/api/auth/me': { id: 1, display_name: '测试成员', role: 'admin' },
      '/api/auth/users': [{ id: 1, display_name: '测试成员', role: 'admin', capacity_hours: 60 }],
      '/api/projects': [{ id: 'P002', name: '真实项目', can_write: true, can_manage: true }],
      '/api/projects/P002/members': [{ user_id: 1, project_role: 'admin' }],
      '/api/stories': [{ id: 'US01', title: '保留的用户故事', description: '', acceptance: '', owner_id: 1, priority: 'Must', sprint: 1, activity: 1, status: 0 }],
      '/api/tasks': [{ id: 'T01', name: '保留的任务', owner_id: 1, hours: 8, week_start: 1, week_end: 2, kanban_card_id: 'US01', story_ref: 'US01', status: 0 }],
      '/api/pool': [{ id: 'R01', title: '保留的需求', description: '', source: '人工录入', priority: 'Could' }],
      '/api/stories/logs': [{ story_id: 'US01', log_type: 'edit', detail: '恢复后的变更记录', created_at: '2026-10-18T10:00:00' }]
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(responses[path] ?? []) })
  })
  await page.goto('/#/projects/P002/board')
  await expect(page.locator('#hintcount')).toContainText('1 条真实故事')
  await expect(page.locator('.project-load-warning')).toContainText('审计服务异常')
  await expect(page.locator('#user-chip')).toContainText('测试成员')
  await expect(page.locator('#login')).not.toBeVisible()
  for (const view of ['overview', 'pool', 'gantt', 'members']) {
    await page.locator(`.navitem[data-view="${view}"]`).click()
    await expect(page.locator(`#view-${view}`)).toBeVisible()
    const data = await page.evaluate(() => ({ stories: window.stories.length, tasks: window.tasks.length, token: localStorage.getItem('aiguanli_token') }))
    expect(data).toEqual({ stories: 1, tasks: 1, token: 'project-loading-test' })
    if (view === 'pool') await expect(page.locator('#pool-list')).toContainText('保留的需求')
  }
  logFailed = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.locator('.project-load-warning')).toHaveCount(0)
  await page.locator('.navitem[data-view="board"]').click()
  await page.locator('.logpanel summary').click()
  await expect(page.locator('#logbody')).toContainText('恢复后的变更记录')
})
