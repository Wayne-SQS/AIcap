import { assertMeetingRetained, assertEmptyMeetingDeletable } from './meeting-deletion-check'
import { test, expect } from '@playwright/test'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'
const aiBase = process.env.AICAP_LIVE_AI_BASE || 'http://127.0.0.1:18190'

test('real Planning modified approval, execution, retries and persisted audit', async ({ page, request }) => {
  await page.goto('/#/meetings')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.locator('#meeting-save-form input[name=title]').fill('Planning真实联调')
  await page.locator('#meeting-save-form textarea').fill('US13 调整到 Sprint 3。')
  await page.locator('#meeting-save-form button').click()
  await expect(page.locator('#saved-transcript')).toHaveText('US13 调整到 Sprint 3。')
  const meetingId = await page.locator('#meeting-select').inputValue()
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const headers = { Authorization: `Bearer ${token}` }
  const base = javaBase
  const read = async path => {
    const response = await request.get(base + path, { headers })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const story = async () => (await read('/api/stories')).find(s => s.id === 'US13')
  const before = await story()
  const tasks = await read('/api/tasks')
  expect(before.sprint).toBe(2)
  await page.getByLabel('会议分析类型').selectOption('planning')
  const panel = page.getByRole('region', { name: '故事Sprint提案', exact: true })
  await panel.getByLabel('目标 Sprint（可选）').selectOption('3')
  await panel.getByRole('button', { name: '分析Sprint Planning', exact: true }).click()
  await expect(panel).toContainText('原始提案：Sprint 2 → Sprint 3', { timeout: 20000 })
  const analysisId = await panel.getByLabel('Planning分析记录').inputValue()
  const recordPath = `/api/meetings/${meetingId}/planning-analyses/${analysisId}`
  const original = await read(recordPath)
  const proposalId = original.result.proposed_actions[0].proposal_id
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  await assertEmptyMeetingDeletable(request, base, headers)
  expect(await story()).toEqual(before)
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('批准后的目标Sprint').selectOption('4')
  await panel.getByLabel('修改原因（必填）').fill('人工确认依赖后调整到 Sprint 4')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的变更：Sprint 2 → Sprint 4')
  expect(await story()).toEqual(before)
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('执行结果：Sprint 2 → Sprint 4')
  expect(await story()).toEqual({ ...before, sprint: 4 })
  expect(await read('/api/tasks')).toEqual(tasks)
  const executions = await read(recordPath + '/proposal-executions')
  expect(executions).toHaveLength(1)
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  const repeat = await request.post(base + recordPath + '/proposal-executions', { headers, data: { proposal_id: proposalId } })
  expect(repeat.status()).toBe(200)
  expect(await repeat.json()).toEqual(executions[0])
  const analyzePath = `${aiBase}/api/meetings/${meetingId}/planning/analyze`
  const retry = await request.post(analyzePath, { headers, data: {
    client_request_id: original.client_request_id, meeting_type: 'sprint_planning', target_sprint: 3
  } })
  expect(retry.status()).toBe(200)
  expect((await retry.json()).analysis_id).toBe(analysisId)
  expect(await read(recordPath)).toEqual(original)
  const logs = (await read('/api/stories/logs')).filter(log => log.story_id === 'US13' && log.log_type === 'edit')
  expect(logs).toHaveLength(1)
  expect(logs[0].id).toBe(executions[0].story_log_id)
  expect((await request.post(base + recordPath + '/proposal-executions', { data: { proposal_id: proposalId } })).status()).toBe(401)
  const login = await request.post(base + '/api/auth/login', { data: { username: '成员5', password: '123456' } })
  expect(login.status()).toBe(200)
  const viewer = { Authorization: `Bearer ${(await login.json()).access_token}` }
  expect((await request.post(base + recordPath + '/proposal-executions', { headers: viewer, data: { proposal_id: proposalId } })).status()).toBe(403)
  expect((await request.post(analyzePath, { headers: viewer, data: { client_request_id: 'viewer-denied', meeting_type: 'sprint_planning' } })).status()).toBe(403)
  await page.reload()
  await page.getByLabel('会议分析类型').selectOption('planning')
  await expect(panel).toContainText(`故事日志 #${executions[0].story_log_id}`)
  await expect(panel).toContainText('执行结果：Sprint 2 → Sprint 4')
  await page.evaluate(() => window.go('board'))
  await expect(page.locator('[data-map-drop^="4-"] [data-id="US13"]')).toBeVisible()
})
