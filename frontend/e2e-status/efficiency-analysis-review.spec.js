import { test, expect } from '@playwright/test'

test('efficiency recommendation appears for review and owner can approve it', async ({ page }) => {
  let submitted = false
  let decision = null
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
    if (path.endsWith('/efficiency-analysis/latest')) body = {
      analysisId: 'run-1', summary: '识别 1 项瓶颈', engine: 'rules_fallback', limitations: [],
      bottlenecks: [{ id: 'B1', title: '任务阻塞', type: 'BLOCKED', severity: 'HIGH',
        affectedTaskCodes: ['T1'], evidence: ['T1 阻塞'], recommendations: [{ id: 'R1', description: '复核阻塞' }] }],
      proposals: submitted ? [{ id: 'proposal-1', recommendation_id: 'R1', status: decision ? 'approved' : 'pending',
        proposal: { title: '任务阻塞', recommendations: [{ description: '复核阻塞' }] } }] : []
    }
    if (path.endsWith('/recommendations/R1/submit')) { submitted = true; body = { id: 'proposal-1', status: 'pending' } }
    if (path.endsWith('/proposals/proposal-1/review')) {
      decision = route.request().postDataJSON()
      body = { id: 'proposal-1', status: 'approved' }
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/agents?agent=efficiency')
  const panel = page.getByLabel('项目效率分析操作区')
  await panel.getByText('查看证据与建议').click()
  await panel.getByRole('button', { name: '提交审核' }).click()
  await expect(panel.getByRole('button', { name: '已提交审核' })).toBeDisabled()
  await panel.getByLabel('审核理由').fill('确认需要复核')
  await panel.getByRole('button', { name: '批准（不执行）' }).click()
  await expect(panel).toContainText('建议已批准')
  expect(decision).toEqual({ decision: 'approve', reason: '确认需要复核' })
})
