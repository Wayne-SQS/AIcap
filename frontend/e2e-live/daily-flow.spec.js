import { assertMeetingRetained, assertEmptyMeetingDeletable } from './meeting-deletion-check'
import { test, expect } from '@playwright/test'

test('real daily analysis, human review, execution, retry and board consistency', async ({ page, request }) => {

  await page.goto('/#/ai')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.locator('#meeting-save-form input[name=title]').fill('每日站会真实联调')
  await page.locator('#meeting-save-form textarea').fill('US13 今天开始开发。')
  await page.locator('#meeting-save-form button').click()
  await expect(page.locator('#saved-transcript')).toHaveText('US13 今天开始开发。')
  const meetingId = await page.locator('#meeting-select').inputValue()
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const headers = { Authorization: `Bearer ${token}` }
  const base = 'http://127.0.0.1:18180'
  const status = async () => (await (await request.get(base + '/api/stories', { headers })).json()).find(s => s.id === 'US13').status
  expect(await status()).toBe(0)
  await page.getByRole('button', { name: '分析每日站会', exact: true }).click()
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel.getByRole('region', { name: '每日站会状态分析' }).getByRole('status')).toContainText('分析已保存', { timeout: 20000 })
  await expect(panel.locator('article')).toContainText('原始提案：待办 → 进行中')
  const analysisId = await page.getByLabel('状态分析记录').inputValue()
  const recordPath = `/api/meetings/${meetingId}/status-analyses/${analysisId}`
  const original = await (await request.get(base + recordPath, { headers })).json()
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  await assertEmptyMeetingDeletable(request, base, headers)
  expect(await status()).toBe(0)
  await page.getByRole('form', { name: '审核状态提案' }).getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel.locator('article')).toContainText('已批准 · 未执行')
  expect(await status()).toBe(0)
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel.locator('article')).toContainText('已批准 · 执行成功')
  expect(await status()).toBe(1)
  const executions = await (await request.get(base + recordPath + '/proposal-executions', { headers })).json()
  expect(executions).toHaveLength(1)
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  const repeat = await request.post(base + recordPath + '/proposal-executions', { headers, data: { proposal_id: 'p1' } })
  expect(repeat.status()).toBe(200)
  expect(await repeat.json()).toEqual(executions[0])
  const savedRetry = await request.post('http://127.0.0.1:18190' + `/api/meetings/${meetingId}/analyze`, {
    headers, data: { client_request_id: original.client_request_id, meeting_type: 'daily_scrum', current_sprint: null }
  })
  expect(savedRetry.status()).toBe(200)
  expect((await savedRetry.json()).analysis_id).toBe(analysisId)
  expect(await (await request.get(base + recordPath, { headers })).json()).toEqual(original)
  const logs = await (await request.get(base + '/api/stories/logs', { headers })).json()
  expect(logs.filter(log => log.story_id === 'US13' && log.log_type === 'move')).toHaveLength(1)
  expect((await request.post(base + recordPath + '/proposal-executions', { data: { proposal_id: 'p1' } })).status()).toBe(401)
  const viewerLogin = await request.post(base + '/api/auth/login', { data: { username: '成员5', password: '123456' } })
  const viewer = { Authorization: `Bearer ${(await viewerLogin.json()).access_token}` }
  expect((await request.post(base + recordPath + '/proposal-executions', { headers: viewer, data: { proposal_id: 'p1' } })).status()).toBe(403)
  expect((await request.post('http://127.0.0.1:18190' + `/api/meetings/${meetingId}/analyze`, { headers: viewer,
    data: { client_request_id: 'viewer-denied', meeting_type: 'daily_scrum' } })).status()).toBe(403)
  await page.evaluate(() => window.go('board'))
  await expect(page.locator('.mapcard[data-id="US13"]')).toContainText('进行中')
})
