import { test, expect } from '@playwright/test'

test('project owner can inspect evidenced risks and send suggestions for review', async ({ page }) => {
  let submitted = false
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
    if (path === '/api/profile-agent/analysis') body = { team_risks: [{ kind: 'overload', member: '成员甲', evidence: '本月任务工时 48h', inference: '可能过载', impact: '交付延期', suggested_action: '核对并调整分工' }] }
    if (path === '/api/profile-agent/risks/submit-suggestions') { submitted = true; body = [41] }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/insights?tab=risk')
  const card = page.getByRole('region', { name: '项目风险预警' })
  await card.getByRole('button', { name: '查看本月风险' }).click()
  await expect(card).toContainText('本月任务工时 48h')
  await expect(card).toContainText('交付延期')
  await card.getByRole('button', { name: '将风险建议送审' }).click()
  await expect(card).toContainText('已送审 1 条风险建议')
  expect(submitted).toBe(true)
})
