import { test, expect } from '@playwright/test'

test('efficiency card shows project evidence and missing-history limit', async ({ page }) => {
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
    if (path.endsWith('/efficiency-insights')) body = {
      project_id: 'P001', basis: 'task_blocked_and_status_history', as_of: '2026-10-23',
      open_tasks: 3, missing_or_mismatched_history: 1, stalled_threshold_days: 14,
      limitations: '无有效状态历史时不推断停滞。',
      improvements: [], issues: [
        { task_code: 'T1', task_name: '接口联调', kind: 'blocked', days_in_status: null, suggestion: '确认阻塞原因。' },
        { task_code: 'T2', task_name: '回归测试', kind: 'stalled', days_in_status: 14, suggestion: '复核进度。' }
      ]
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目效率分析' })
  await expect(card).toContainText('识别到 2 项待复核瓶颈')
  await expect(card).toContainText('T1 · 接口联调：任务标记为阻塞')
  await expect(card).toContainText('T2 · 回归测试：进行中状态已持续 14 天')
  await expect(card).toContainText('1 项缺少有效状态历史')
  await expect(card).toContainText('无有效状态历史时不推断停滞')
})

test('human confirmation creates a linked improvement task and tracks completion', async ({ page }) => {
  let created = false
  let finished = false
  let posted = null
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/auth/users') body = [{ id: 1, role: 'owner', display_name: '负责人' }]
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path === '/api/projects/P001/members') body = [{ user_id: 1, project_role: 'owner' }]
    if (path.endsWith('/efficiency-insights')) body = {
      project_id: 'P001', basis: 'task_blocked_and_status_history', as_of: '2026-10-23',
      open_tasks: 1, missing_or_mismatched_history: 0, stalled_threshold_days: 14,
      limitations: '需人工复核。',
      issues: [{ task_code: 'T1', task_name: '接口联调', kind: 'blocked', days_in_status: 2, suggestion: '确认阻塞原因。' }],
      improvements: created ? [{ source_task_code: 'T1', issue_kind: 'blocked', improvement_task_code: 'T99', improvement_status: finished ? 2 : 0, improvement_progress: finished ? 100 : 0 }] : []
    }
    if (path.endsWith('/T1/improvements')) {
      posted = route.request().postDataJSON()
      created = true
      body = { source_task_code: 'T1', issue_kind: 'blocked', improvement_task_code: 'T99', improvement_status: 0 }
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目效率分析' })
  await card.getByRole('button', { name: '确认并创建改进任务' }).click()
  await card.getByLabel('工时').fill('2')
  await card.getByLabel('开始周').fill('5')
  await card.getByLabel('结束周').fill('5')
  await card.getByRole('button', { name: '确认创建' }).click()
  await expect(card).toContainText('T1 → T99：待办')
  expect(posted).toEqual({ issue_kind: 'blocked', owner_id: 1, hours: 2, week_start: 5, week_end: 5 })
  finished = true
  await card.getByRole('button', { name: '刷新分析' }).click()
  await expect(card).toContainText('T1 → T99：已完成')
})
