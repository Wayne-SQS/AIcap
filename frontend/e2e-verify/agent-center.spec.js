import { test, expect } from '@playwright/test'

async function mockOnline(page, role = 'member') {
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'test-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    let body = []
    if (url.pathname === '/api/health') body = {}
    if (url.pathname === '/api/auth/me') body = { id: 3, display_name: '测试用户', role, can_write: role !== 'viewer' }
    if (url.pathname === '/api/projects') body = ['P001', 'P002'].map(id => ({ id, name: `项目 ${id}`, can_write: role !== 'viewer', can_manage: false }))
    if (url.pathname === '/api/dashboard') body = { total: 0, done: 0, doing: 0, todo: 0, percent: 0, by_sprint: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

test('调用中心离线不发起智能体请求，切换选择可刷新恢复', async ({ page }) => {
  const agentRequests = []
  await page.route('**/api/**', route => {
    if (!new URL(route.request().url()).pathname.startsWith('/api/')) return route.continue()
    if (route.request().url().includes('agent')) agentRequests.push(route.request().url())
    return route.fulfill({ status: 503, body: '{}' })
  })
  await page.goto('/#/agents')
  await expect(page.getByRole('heading', { name: '智能体调用中心' })).toBeVisible()
  await expect(page.locator('.agent-choice')).toHaveCount(5)
  await expect(page.getByText('连接在线服务后即可调用智能体')).toBeVisible()
  await page.getByRole('button', { name: /项目规划智能体 PLANNING AGENT/ }).click()
  await expect(page).toHaveURL(/agent=planning/)
  await page.reload()
  await expect(page.locator('#selected-agent-title')).toHaveText('项目规划智能体')
  await expect(page.getByRole('button', { name: '登录在线服务' })).toBeVisible()
  expect(agentRequests).toEqual([])
})

test('调用中心真实接口契约：规划生成、结果展示、确认执行及项目隔离', async ({ page }) => {
  await mockOnline(page)
  const writes = []
  const run = { id: 'run-1', status: 'waiting_confirmation', request_text: '查看任务排期', events: [], result: { summary: '规划草案已生成', answer: '调整排期后可按时交付。', actions: [], warnings: [] } }
  await page.route('**/api/planning-agent/**', async route => {
    const request = route.request()
    if (request.method() === 'POST') writes.push({ url: request.url(), body: request.postDataJSON() })
    const confirmed = new URL(request.url()).pathname.endsWith('/confirm')
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...run, status: confirmed ? 'completed' : run.status }) })
  })
  await page.goto('/#/projects/P001/agents?agent=planning')
  await expect(page.getByRole('button', { name: '生成规划' })).toBeDisabled()
  await page.getByRole('textbox', { name: '画图智能体请求' }).fill('查看任务排期')
  await page.getByRole('button', { name: '生成规划' }).click()
  await expect(page.getByText('规划草案已生成')).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0].body).toEqual({ request: '查看任务排期', project_id: 'P001' })
  await page.getByRole('button', { name: '确认执行', exact: true }).click()
  await expect(page.locator('.planning-run-head')).toContainText('已完成')
  expect(writes).toHaveLength(2)
  expect(writes[1].url).toContain('/confirm?projectId=P001')
  await page.getByRole('button', { name: /当前项目/ }).click()
  await page.getByRole('option', { name: '项目 P002' }).click()
  await expect(page).toHaveURL(/projects\/P002\/agents/)
  await page.getByRole('button', { name: /项目规划智能体 PLANNING AGENT/ }).click()
  await expect(page.getByRole('textbox', { name: '画图智能体请求' })).toBeEmpty()
  await expect(page.getByText('规划草案已生成')).toHaveCount(0)
})

test('规划读取失败可重试，查看者不能确认执行', async ({ page }) => {
  await mockOnline(page, 'viewer')
  let reads = 0
  await page.route('**/api/planning-agent/**', async route => {
    if (route.request().method() === 'GET' && ++reads === 1) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: '模型服务暂不可用' }) })
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'run-2', status: 'waiting_confirmation', events: [], result: { summary: '只读规划', actions: [] } }) })
  })
  await page.goto('/#/projects/P001/agents?agent=planning')
  await page.getByRole('textbox', { name: '画图智能体请求' }).fill('查看任务')
  await page.getByRole('button', { name: '生成规划' }).click()
  await expect(page.getByRole('alert')).toContainText('模型服务暂不可用')
  await page.getByRole('button', { name: '重试读取结果' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByText('当前账号为只读角色')).toBeVisible()
  await expect(page.getByRole('button', { name: '确认执行', exact: true })).toHaveCount(0)
})

test('五类智能体在当前页加载，保留统一导航且没有运行时异常', async ({ page }, testInfo) => {
  await mockOnline(page)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1050 })
  await page.goto('/#/projects/P001/agents')
  await expect(page.locator('.agent-content #meeting-refresh')).toBeVisible()
  for (const [name, selector] of [
    ['任务提交智能体 COMMIT AGENT', '.agent-content .submit-agent'],
    ['成员画像智能体 PROFILE AGENT', '.agent-content .profile-agent'],
    ['项目生成智能体 PROJECT GENERATOR', '.agent-content #view-project-generator'],
    ['项目规划智能体 PLANNING AGENT', '.agent-content .planning-agent-panel']
  ]) {
    await page.getByRole('button', { name, exact: false }).click()
    await expect(page.locator(selector)).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
  }
  await page.screenshot({ path: testInfo.outputPath('agent-center.png'), fullPage: true })
  expect(errors).toEqual([])
})
