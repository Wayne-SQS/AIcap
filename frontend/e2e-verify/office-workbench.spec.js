/* 旧「智能体调用中心」页已删除（2026-10-27，与 AI 办公室重复）。
   这里接管原来 agent-center.spec.js 里仍有价值的契约：
   1. `/agents` 深链重定向进办公室对应工作台 tab（效率分析也是工作台一页）；
   2. 规划面板的生成/确认契约（原来靠调用中心承载，现在挂在办公室右栏）；
   3. 只读角色不能确认执行、读取失败可重试；
   4. 项目隔离：另一个项目打开同一 tab 是空的；
   5. 侧栏「智能协作」只剩 AI 办公室 + 提案审核中心两处入口。 */
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
    if (url.pathname.endsWith('/summary')) body = { pending: 0, meetings: [], executions: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function mockPlanning(page, onWrite = () => {}) {
  const run = { id: 'run-1', status: 'waiting_confirmation', request_text: '查看任务排期', events: [], result: { summary: '规划草案已生成', answer: '调整排期后可按时交付。', actions: [], warnings: [] } }
  await page.route('**/api/planning-agent/**', async route => {
    const request = route.request()
    if (request.method() === 'POST') onWrite(request)
    const confirmed = new URL(request.url()).pathname.endsWith('/confirm')
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...run, status: confirmed ? 'completed' : run.status }) })
  })
}

test('旧调用中心深链重定向到办公室工作台，效率分析也是工作台一页', async ({ page }) => {
  await mockOnline(page)

  await page.goto('/#/projects/P001/agents?agent=planning')
  await expect(page).toHaveURL(/projects\/P001\/office\?(.*&)?tab=planning/)
  await expect(page.getByRole('textbox', { name: '画图智能体请求' })).toBeVisible()

  await page.goto('/#/projects/P001/agents?agent=efficiency&analysisId=a-1')
  await expect(page).toHaveURL(/projects\/P001\/office\?(.*&)?tab=efficiency/)
  await expect(page.locator('.efficiency-analysis-panel')).toBeVisible()
})

test('规划面板契约：生成与确认执行（在办公室右栏）', async ({ page }) => {
  await mockOnline(page)
  const writes = []
  await mockPlanning(page, request => writes.push({ url: request.url(), body: request.postDataJSON() }))

  await page.goto('/#/projects/P001/office?tab=planning')
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
})

test('项目隔离：换到另一个项目同一 tab 不保留上一项目的草案', async ({ page }) => {
  await mockOnline(page)
  await mockPlanning(page)

  await page.goto('/#/projects/P001/office?tab=planning')
  await page.getByRole('textbox', { name: '画图智能体请求' }).fill('查看任务排期')
  await page.getByRole('button', { name: '生成规划' }).click()
  await expect(page.getByText('规划草案已生成')).toBeVisible()

  // 抽屉是模态（挡住侧栏切换项目），先关掉再按 URL 进 P002
  await page.keyboard.press('Escape')
  await page.goto('/#/projects/P002/office?tab=planning')
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
  await page.goto('/#/projects/P001/office?tab=planning')
  await page.getByRole('textbox', { name: '画图智能体请求' }).fill('查看任务')
  await page.getByRole('button', { name: '生成规划' }).click()
  await expect(page.getByRole('alert')).toContainText('模型服务暂不可用')
  await page.getByRole('button', { name: '重试读取结果' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByText('当前账号为只读角色')).toBeVisible()
  await expect(page.getByRole('button', { name: '确认执行', exact: true })).toHaveCount(0)
})

test('侧栏智能协作只剩 AI 办公室与提案审核中心', async ({ page }) => {
  await mockOnline(page)
  await page.goto('/#/projects/P001/office')
  await expect(page.getByRole('heading', { name: 'AI 办公室' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'AI 办公室' })).toHaveCount(1)
  await expect(page.getByRole('link', { name: '提案审核中心' })).toHaveCount(1)
  await expect(page.getByRole('link', { name: '智能体调用中心' })).toHaveCount(0)
})
