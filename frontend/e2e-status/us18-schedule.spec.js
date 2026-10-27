import { test, expect } from '@playwright/test'

test('US18 suggests an evidence based schedule and waits for confirmation', async ({ page }) => {
  let request = null
  let confirmations = 0
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
    if (path === '/api/planning-agent/runs' && route.request().method() === 'POST') {
      request = route.request().postDataJSON()
      body = { id: 'schedule-1', status: 'waiting_confirmation' }
    }
    if (path === '/api/planning-agent/runs/schedule-1') body = {
      id: 'schedule-1', status: 'waiting_confirmation', request_text: request?.request,
      result: {
        summary: '根据依赖和每周容量建议调整 T1',
        answer: 'T1 是 Must，依赖已完成；W3 还有 8 小时容量。',
        actions: [{ type: 'UPDATE_TASK_SCHEDULE', task_id: 'T1', start_week: 3, end_week: 3 }],
        impact_analysis: {}, warnings: ['执行前确认实际剩余工时']
      }
    }
    if (path.endsWith('/schedule-1/confirm')) confirmations++
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/office?tab=planning')
  const panel = page.locator('.planning-agent-panel')
  await panel.getByRole('button', { name: '智能排期建议（US18）' }).click()
  await expect(panel).toContainText('根据依赖和每周容量建议调整 T1')
  await expect(panel.getByRole('button', { name: '确认执行' })).toBeVisible()
  expect(request.project_id).toBe('P001')
  expect(request.request).toContain('优先级与依赖')
  expect(request.request).toContain('每周容量')
  expect(confirmations).toBe(0)
})
