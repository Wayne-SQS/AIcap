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
    if (path.endsWith('/progress-forecast/records')) body = { project_id: 'P001', as_of: '2026-10-23', records: [], limitations: '对账只比较任务数量与计划日期。' }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/insights?tab=forecast')
  const card = page.getByRole('region', { name: '项目进度预测' })
  await expect(card).toContainText('历史数据不足，暂不提供交付日期')
  await expect(card).not.toContainText('2026-11-24')
  enoughHistory = true
  await card.getByRole('button', { name: '刷新预测' }).click()
  await expect(card).toContainText('预计 2026-11-24')
  await expect(card).toContainText('未考虑新增需求和阻塞')
  expect(calls).toEqual(['/api/projects/P001/progress-forecast', '/api/projects/P001/progress-forecast'])
})

const baseline = (overrides = {}) => ({
  project_id: 'P001', id: 7, record_status: 'proposed', forecast_status: 'forecast',
  history_start: '2026-09-30', history_end: '2026-10-23', observed_days: 24,
  tasks_total: 7, tasks_open: 4, tasks_done: 3, recent_completions: 3,
  completions_per_week: 0.88, estimated_remaining_days: 32, estimated_date: '2026-11-24',
  assumptions: '按最近最多42天任务完成吞吐线性外推。', requested_by: 1,
  requested_at: '2026-10-23T20:40', decided_by: null, decided_at: null, decision_note: null,
  reconciliation: { completed_since: 0, open_now: 4, due_in_days: 32, verdict: 'not_baseline' },
  ...overrides
})

test('a recorded baseline needs an explicit decision and is reconciled afterwards', async ({ page }) => {
  let created = false
  let decided = false
  const posts = []
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (path === '/api/health') return route.fulfill({ json: { status: 'ok' } })
    if (path === '/api/auth/me') return route.fulfill({ json: { id: 1, role: 'owner', display_name: '负责人' } })
    if (path === '/api/projects') return route.fulfill({ json: [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }] })
    if (method === 'POST' && path.endsWith('/progress-forecast/records')) {
      posts.push(path)
      created = true
      return route.fulfill({ json: baseline() })
    }
    if (method === 'POST' && path.endsWith('/decision')) {
      posts.push(path)
      decided = true
      return route.fulfill({ json: baseline({ record_status: 'accepted', decided_by: 1, decided_at: '2026-10-23T20:41',
        reconciliation: { completed_since: 0, open_now: 4, due_in_days: 32, verdict: 'within_plan' } }) })
    }
    if (path.endsWith('/progress-forecast/records')) {
      const records = decided
        ? [baseline({ record_status: 'accepted', decided_by: 1, decided_at: '2026-10-23T20:41',
            reconciliation: { completed_since: 0, open_now: 4, due_in_days: 32, verdict: 'within_plan' } })]
        : created ? [baseline()] : []
      return route.fulfill({ json: { project_id: 'P001', as_of: '2026-10-23', records, limitations: '对账只比较任务数量与计划日期。' } })
    }
    if (path.endsWith('/progress-forecast')) {
      return route.fulfill({ json: {
        project_id: 'P001', basis: 'task_status_history', status: 'forecast',
        history_start: '2026-09-30', history_end: '2026-10-23', observed_days: 24,
        tasks_total: 7, tasks_open: 4, tasks_done: 3, recent_completions: 3,
        completions_per_week: 0.88, estimated_remaining_days: 32, estimated_date: '2026-11-24',
        assumptions: '按最近最多42天任务完成吞吐线性外推。'
      } })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/#/projects/P001/insights?tab=baseline')
  const panel = page.getByRole('region', { name: '进度预测基线' })
  await expect(panel).toContainText('尚无基线。固化当前预测后由负责人采纳，才能事后对账')
  await panel.getByRole('button', { name: '固化当前预测' }).click()
  await expect(panel).toContainText('待确认')
  await expect(panel).toContainText('#7 · 2026-11-24')
  expect(posts).toEqual(['/api/projects/P001/progress-forecast/records'])
  await panel.getByRole('button', { name: '采纳为基线' }).click()
  await expect(panel).toContainText('已采纳')
  await expect(panel).toContainText('此后完成 0 项，剩余 4 项，计划内')
  await expect(panel).not.toContainText('待确认')
  expect(posts).toEqual(['/api/projects/P001/progress-forecast/records',
    '/api/projects/P001/progress-forecast/records/7/decision'])
})

test('a read-only account can see the baseline but cannot record or decide it', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/health') return route.fulfill({ json: { status: 'ok' } })
    if (path === '/api/auth/me') return route.fulfill({ json: { id: 5, role: 'viewer', display_name: '只读成员' } })
    if (path === '/api/projects') return route.fulfill({ json: [{ id: 'P001', name: '测试项目', can_write: false, can_manage: false }] })
    if (path.endsWith('/progress-forecast/records')) {
      return route.fulfill({ json: { project_id: 'P001', as_of: '2026-10-23', records: [baseline()],
        limitations: '对账只比较任务数量与计划日期。' } })
    }
    if (path.endsWith('/progress-forecast')) {
      return route.fulfill({ json: {
        project_id: 'P001', basis: 'task_status_history', status: 'forecast',
        history_start: '2026-09-30', history_end: '2026-10-23', observed_days: 24,
        tasks_total: 7, tasks_open: 4, tasks_done: 3, recent_completions: 3,
        completions_per_week: 0.88, estimated_remaining_days: 32, estimated_date: '2026-11-24',
        assumptions: '按最近最多42天任务完成吞吐线性外推。'
      } })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/#/projects/P001/insights?tab=baseline')
  const panel = page.getByRole('region', { name: '进度预测基线' })
  await expect(panel.getByRole('button', { name: '固化当前预测' })).toBeDisabled()
  await expect(panel.getByRole('button', { name: '采纳为基线' })).toBeDisabled()
  await expect(panel).toContainText('待确认')
})
