import { test, expect } from '@playwright/test'

test('progress forecast withholds a date until history is sufficient and exposes assumptions', async ({ page }) => {
  const calls = []
  let enoughHistory = false
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path.endsWith('/progress-forecast')) {
      calls.push(path)
      body = {
        project_id: 'P001', basis: 'task_status_history', status: enoughHistory ? 'forecast' : 'insufficient_data',
        history_start: '2026-09-30', history_end: '2026-10-23', observed_days: 24,
        tasks_total: 7, tasks_open: 4, tasks_done: 3, recent_completions: enoughHistory ? 3 : 1,
        completions_per_week: enoughHistory ? 0.88 : null,
        estimated_remaining_days: enoughHistory ? 32 : null,
        estimated_date: enoughHistory ? '2026-11-24' : null,
        assumptions: '按最近最多42天任务完成吞吐线性外推；未考虑新增需求和阻塞。'
      }
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目进度预测' })
  await expect(card).toContainText('历史数据不足，暂不提供交付日期')
  await expect(card).not.toContainText('2026-11-24')
  enoughHistory = true
  await card.getByRole('button', { name: '刷新预测' }).click()
  await expect(card).toContainText('预计 2026-11-24')
  await expect(card).toContainText('未考虑新增需求和阻塞')
  expect(calls).toEqual(['/api/projects/P001/progress-forecast', '/api/projects/P001/progress-forecast'])
})
