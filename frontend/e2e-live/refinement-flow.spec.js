import { assertMeetingRetained, assertEmptyMeetingDeletable } from './meeting-deletion-check'
import { test, expect } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

test('real Refinement completion creates one story with durable audit', async ({ page, request }) => {
  const transcript = process.env.AICAP_LIVE_REAL_MODEL === '1'
    ? '主持人：确认新增一个独立需求，标题为“导出周报CSV”。描述、验收标准、优先级、Sprint和业务活动编号本次均未讨论，请保留待确认，不补默认值。'
    : '决定新增周报导出需求，验收标准和排期待确认。'
  await page.goto('/#/ai')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.locator('#meeting-save-form input[name=title]').fill('Refinement真实联调')
  await page.locator('#meeting-save-form textarea').fill(transcript)
  await page.locator('#meeting-save-form button').click()
  await expect(page.locator('#saved-transcript')).toHaveText(transcript)
  const meetingId = await page.locator('#meeting-select').inputValue()
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const headers = { Authorization: `Bearer ${token}` }, base = 'http://127.0.0.1:18180'
  const read = async path => {
    const response = await request.get(base + path, { headers })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const stories = await read('/api/stories'), tasks = await read('/api/tasks'), profiles = await read('/api/members/profiles')
  await page.getByRole('button', { name: '打开会议 Backlog Refinement' }).click()
  const panel = page.getByRole('region', { name: 'Refinement新故事提案', exact: true })
  await panel.getByRole('button', { name: '分析Backlog Refinement', exact: true }).click()
  await expect(panel).toContainText('原提案待补齐', { timeout: 60000 })
  const analysisId = await panel.getByLabel('Refinement分析记录').inputValue()
  const recordPath = `/api/meetings/${meetingId}/refinement-analyses/${analysisId}`
  const original = await read(recordPath)
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  await assertEmptyMeetingDeletable(request, base, headers)
  if (process.env.AICAP_LIVE_ARTIFACT_DIR) writeFileSync(join(process.env.AICAP_LIVE_ARTIFACT_DIR, 'refinement-analysis.json'), JSON.stringify(original, null, 2))
  expect(original.result.proposed_actions).toHaveLength(1)
  const proposalId = original.result.proposed_actions[0].proposal_id
  expect(original.result.proposed_actions[0].changes).toEqual({ title: '导出周报CSV', description: null, acceptance: null, priority: null, sprint: null, activity: null })
  const execute = () => request.post(base + recordPath + '/proposal-executions', { headers, data: { proposal_id: proposalId } })
  expect((await execute()).status()).toBe(409)
  await panel.getByLabel('批准后的描述').fill('支持导出周报')
  await panel.getByLabel('批准后的验收标准').fill('下载CSV包含全部周报字段')
  await panel.getByLabel('批准后的优先级').selectOption('Should')
  await panel.getByLabel('批准后的Sprint').selectOption('3')
  await panel.getByLabel('批准后的活动').selectOption('2')
  await panel.getByLabel('修改原因（必填）').fill('人工补齐验收和排期')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的故事')
  expect(await read('/api/stories')).toEqual(stories)
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('执行结果已确认')
  const executions = await read(recordPath + '/proposal-executions')
  expect(executions).toHaveLength(1)
  await assertMeetingRetained(request, base, headers, meetingId, recordPath)
  const id = executions[0].story_id, after = await read('/api/stories')
  expect(after).toHaveLength(stories.length + 1)
  expect(after.filter(story => story.id !== id)).toEqual(stories)
  expect(after.find(story => story.id === id)).toMatchObject({ title: '导出周报CSV', description: '支持导出周报', acceptance: '下载CSV包含全部周报字段', priority: 'Should', sprint: 3, activity: 2, status: 0, owner_id: null })
  const logs = (await read('/api/stories/logs')).filter(log => log.story_id === id)
  expect(logs).toHaveLength(1)
  expect(logs[0]).toMatchObject({ id: executions[0].story_log_id, log_type: 'create' })
  expect(JSON.parse(logs[0].detail).changes.title).toBe('导出周报CSV')
  for (const response of await Promise.all([execute(), execute()])) {
    expect(response.status()).toBe(200); expect(await response.json()).toEqual(executions[0])
  }
  expect(await read('/api/stories')).toEqual(after)
  expect((await read('/api/stories/logs')).filter(log => log.story_id === id)).toEqual(logs)
  const analyzePath = `http://127.0.0.1:18190/api/meetings/${meetingId}/refinement/analyze`
  const retry = await request.post(analyzePath, { headers, data: { client_request_id: original.client_request_id, meeting_type: 'backlog_refinement' } })
  expect(retry.status()).toBe(200)
  expect((await retry.json()).analysis_id).toBe(analysisId)
  expect(await read(recordPath)).toEqual(original)
  if (process.env.AICAP_LIVE_ARTIFACT_DIR) writeFileSync(join(process.env.AICAP_LIVE_ARTIFACT_DIR, 'refinement-execution.json'), JSON.stringify({ reviews: await read(recordPath + '/proposal-reviews'), executions, story: after.find(story => story.id === id), logs }, null, 2))
  expect(await read('/api/tasks')).toEqual(tasks)
  expect(await read('/api/members/profiles')).toEqual(profiles)
  expect((await request.post(base + recordPath + '/proposal-executions', { data: { proposal_id: 'p1' } })).status()).toBe(401)
  const login = await request.post(base + '/api/auth/login', { data: { username: '成员5', password: '123456' } })
  const viewer = { Authorization: `Bearer ${(await login.json()).access_token}` }
  expect((await request.post(base + recordPath + '/proposal-executions', { headers: viewer, data: { proposal_id: 'p1' } })).status()).toBe(403)
  expect((await request.post(base + recordPath + '/proposal-reviews', { headers: viewer, data: { proposal_id: 'p1', decision: 'approve', reason: '' } })).status()).toBe(403)
  expect((await request.post(analyzePath, { headers: viewer, data: { client_request_id: 'viewer-denied', meeting_type: 'backlog_refinement' } })).status()).toBe(403)
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Backlog Refinement' }).click()
  await expect(panel).toContainText(`已创建故事：${id}`)
  await expect(panel).toContainText(`故事日志 #${logs[0].id}`)
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
  await page.evaluate(() => window.go('board'))
  await expect(page.locator(`.mapcard[data-id="${id}"]`)).toContainText('导出周报CSV')
})
