import { test, expect } from '@playwright/test'

async function setup(page, online = false) {
  await page.addInitScript(online => {
    window.__AICAP_API_BASE__ = 'http://api.test'
    window.__AICAP_ORCH_BASE__ = 'http://office.test'
    localStorage.removeItem('aicap_supervisor_session')
    if (online) {
      localStorage.setItem('aiguanli_token', 'office-token')
      localStorage.setItem('aiguanli-current-project', 'P002')
    }
  }, online)
  await page.route('http://api.test/**', route => {
    const path = new URL(route.request().url()).pathname
    const data = {
      '/api/health': {}, '/api/auth/me': { id: 1, display_name: '设计验收', role: 'admin' },
      '/api/projects': [{ id: 'P002', name: '爱管理', can_write: true, can_manage: true }],
      '/api/review-queue/summary': { pendingCount: 3, executionCount: 1, recentMeeting: null, latest: null },
      '/api/dashboard': { total: 0, done: 0, doing: 0, todo: 0, percent: 0, by_sprint: [] }
    }
    return route.fulfill({ status: !online && path === '/api/health' ? 503 : 200, contentType: 'application/json', body: JSON.stringify(data[path] ?? []) })
  })
  await page.route('http://office.test/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ok', java: online, llm_configured: online }) }))
}

test('首屏为四人像素房间，默认不加载工作面板，桌面与手机均无横向溢出', async ({ page }, info) => {
  await setup(page, true)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1100 })
  await page.goto('/#/projects/P002/office')
  await expect(page.locator('.room-desk')).toHaveCount(4)
  await expect(page.locator('.office-drawer')).not.toBeVisible()
  await expect(page.locator('.wb')).toHaveCount(0)
  await expect(page.locator('.office-inbox b')).toHaveText('3')
  await page.screenshot({ path: info.outputPath('office-desktop.png'), fullPage: true, animations: 'disabled' })
  for (const width of [1024, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    for (const desk of await page.locator('.room-desk').all()) await expect(desk).toBeVisible()
  }
  await expect(page.locator('.office-header')).toBeInViewport()
  await page.screenshot({ path: info.outputPath('office-mobile.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '暂停动效' }).click()
  await expect(page.locator('.pixel-room')).toHaveClass(/motion-paused/)
  expect(errors).toEqual([])
})

test('主管抽屉可关闭和恢复草稿，Esc 返回工位且浮窗入口共用办公室抽屉', async ({ page }, info) => {
  await setup(page, true)
  await page.goto('/#/projects/P002/office')
  const trigger = page.getByRole('button', { name: '打开本的项目主管' })
  await trigger.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.locator('#office-drawer-title')).toHaveText('本 · 项目主管')
  await page.getByRole('textbox', { name: '向本提问' }).fill('想讨论一下下周的计划')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(trigger).toBeFocused()
  await trigger.click()
  await expect(page.getByRole('textbox', { name: '向本提问' })).toHaveValue('想讨论一下下周的计划')
  await page.screenshot({ path: info.outputPath('office-drawer.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '关闭工作抽屉' }).click()
  await page.locator('#supervisor-avatar').click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.locator('#supervisor-chat')).toHaveCount(0)
})

test('同事抽屉联动工作台，键盘切换、会议深链与项目路由保留', async ({ page }) => {
  await setup(page)
  await page.goto('/#/office')
  await page.getByRole('button', { name: '打开衡的画像同事' }).click()
  await expect(page.locator('#wb-tab-profile')).toHaveAttribute('aria-selected', 'true')
  await page.locator('#wb-tab-profile').press('ArrowRight')
  await expect(page.locator('#wb-tab-submit')).toBeFocused()
  await expect(page.locator('#wb-tab-submit')).toHaveAttribute('aria-selected', 'true')
  await page.locator('.office-drawer-switch button').filter({ hasText: '绘' }).click()
  await expect(page.locator('.planning-agent-panel')).toBeVisible()
  await page.getByRole('button', { name: '关闭工作抽屉' }).click()
  await expect(page.getByRole('button', { name: '打开衡的画像同事' })).toBeFocused()
  await page.getByRole('button', { name: '打开绘的规划同事' }).click()
  await expect(page.locator('.planning-agent-panel')).toBeVisible()
  await page.getByRole('button', { name: '关闭工作抽屉' }).click()
  await page.goto('/#/meetings?meetingId=m7&type=planning&analysisId=a9&fromReview=1&status=reviewed')
  await expect(page.locator('.office-drawer')).toBeVisible()
  await expect(page.locator('#wb-tab-meeting')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('button', { name: '← 返回审核中心（保留筛选）' })).toBeVisible()
  await page.goto('/#/projects/P002/overview')
  await expect(page.locator('#view-overview')).toBeVisible()
  await page.goto('/#/projects/P002/board')
  await expect(page.locator('#view-board')).toBeVisible()
})

test('真实对话请求契约和 SSE 结果同时更新对话、白板及过程抽屉', async ({ page }) => {
  await setup(page, true)
  let payload
  await page.route('http://office.test/chat', route => {
    payload = route.request().postDataJSON()
    expect(route.request().headers().authorization).toBe('Bearer office-token')
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ session_id: 'office-s1', run_id: 'office-r1' }) })
  })
  const events = [
    { seq: 1, type: 'session.start', data: {} },
    { seq: 2, type: 'tool.call', data: { name: 'project_summary' } },
    { seq: 3, type: 'run.end', data: { output: JSON.stringify({ answer: '当前项目有 16 条任务。' }), tool_calls: 1 } }
  ].map(event => ({ ...event, session_id: 'office-s1', run_id: 'office-r1', ts: '2026-10-18T04:00:00Z' }))
  await page.route('http://office.test/chat/stream?**', route => route.fulfill({ status: 200, contentType: 'text/event-stream', body: events.map(event => `id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`).join('') }))
  await page.goto('/#/projects/P002/office')
  await page.getByRole('button', { name: '打开本的项目主管' }).click()
  await page.getByRole('textbox', { name: '向本提问' }).fill('现在有几个任务？')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('当前项目有 16 条任务。', { exact: true })).toBeVisible()
  expect(payload).toMatchObject({ utterance: '现在有几个任务？', project_id: 'P002' })
  await page.getByRole('button', { name: '关闭工作抽屉' }).click()
  await expect(page.locator('.room-whiteboard')).toContainText('答复已整理好')
  await page.getByRole('button', { name: '打开办公室完整过程记录' }).click()
  await expect(page.locator('.office-drawer-journal')).toContainText('查 项目总览')
  await expect(page.locator('.office-journal-title')).toContainText('2 条记录')
})
