import { test, expect } from '@playwright/test'
import { assertEmptyMeetingDeletable } from './meeting-deletion-check'
import { createHash } from 'node:crypto'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:18180'
const aiBase = process.env.AICAP_LIVE_AI_BASE || 'http://127.0.0.1:18190'

test('real Assignment save review execute, conflicts, retries and durable audit', async ({ page, request }) => {
  await page.goto('/#/meetings')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.locator('#meeting-save-form input[name=title]').fill('Assignment真实联调')
  await page.locator('#meeting-save-form textarea').fill('讨论US13分工，技能和容量由人工核对。')
  await page.locator('#meeting-save-form button').click()
  await expect(page.locator('#saved-transcript')).toHaveText('讨论US13分工，技能和容量由人工核对。')
  const meetingId = await page.locator('#meeting-select').inputValue()
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const headers = { Authorization: `Bearer ${token}` }, base = javaBase
  const path = `/api/meetings/${meetingId}/assignment-suggestions`
  const read = async p => { const r = await request.get(base + p, { headers }); expect(r.status()).toBe(200); return r.json() }
  const before = await read('/api/stories'), tasks = await read('/api/tasks'), profiles = await read('/api/members/profiles')
  const original = before.find(s => s.id === 'US13')
  await page.locator('details').filter({ hasText: 'Assignment 分配建议' }).locator('summary').click()
  const panel = page.getByRole('region', { name: 'Assignment分配候选', exact: true })
  const history = page.getByRole('region', { name: '分配建议历史与审核', exact: true })
  await panel.getByLabel('待分配故事').selectOption('US13')
  await panel.getByLabel('技能名称', { exact: true }).fill('Python')
  await panel.getByRole('button', { name: '保存分配建议', exact: true }).click()
  await expect(panel.getByText('分配建议已保存，请在下方核对保存的快照并审核。', { exact: true })).toBeVisible({ timeout: 20000 })
  const saved = (await read(path))[0], recordPath = path + '/' + saved.id
  const chosen = saved.result.candidates.find(c => c.member_id !== original.owner_id)
  expect(chosen).toBeTruthy()
  expect(saved.review).toBeNull()
  expect(await read('/api/stories')).toEqual(before)
  expect((await request.post(base + recordPath + '/execute', { headers, data: {} })).status()).toBe(409)
  expect((await request.delete(base + `/api/meetings/${meetingId}`, { headers })).status()).toBe(409)
  // Byte-integrity fixture only: no decoder/STT or real human recording is involved.
  const audio = Buffer.alloc(2048)
  audio.set([0x49, 0x44, 0x33, 3], 0); audio.set([0xff, 0xfb, 0x90, 0x64], 10)
  const uploaded = await request.post(base + `/api/meetings/${meetingId}/audio`, {
    headers, multipart: { file: { name: 'input-fixture.mp3', mimeType: 'audio/mpeg', buffer: audio }, duration_ms: '1000' }
  })
  expect(uploaded.status()).toBe(200)
  const meta = await uploaded.json()
  const preparePath = `${aiBase}/api/meetings/${meetingId}/transcription/prepare`
  const prepared = await request.post(preparePath, { headers, data: { audio_id: meta.id } })
  expect(prepared.status()).toBe(200)
  expect(await prepared.json()).toMatchObject({ audio_id: meta.id, meeting_id: meetingId, byte_size: audio.length,
    sha256: createHash('sha256').update(audio).digest('hex'), reported_duration_ms: 1000,
    duration_source: 'upload_metadata_unverified', transcription_status: 'not_started', diarization_status: 'not_started' })
  expect((await request.post(preparePath, { data: { audio_id: meta.id } })).status()).toBe(401)
  expect((await request.post(preparePath, { headers, data: { audio_id: 'not-in-this-meeting' } })).status()).toBe(404)
  await assertEmptyMeetingDeletable(request, base, headers)
  await history.getByLabel('分配给成员').selectOption(String(chosen.member_id))
  await history.getByLabel('分配审核理由').fill('真实联调人工核对，明确知悉容量未验证')
  await history.getByRole('checkbox').check()
  await history.getByRole('button', { name: '提交分配审核' }).click()
  await expect(history).toContainText(`批准负责人：#${chosen.member_id}`)
  expect(await read('/api/stories')).toEqual(before)
  await history.getByRole('button', { name: '执行已批准分配' }).click()
  await expect(history).toContainText('分配已执行', { timeout: 15000 })
  const done = await read(recordPath), execution = done.review.execution
  expect(await read('/api/stories')).toEqual(before.map(s => s.id === 'US13' ? { ...s, owner_id: chosen.member_id } : s))
  expect(await read('/api/tasks')).toEqual(tasks)
  expect(await read('/api/members/profiles')).toEqual(profiles)
  const repeats = await Promise.all([1, 2].map(() => request.post(base + recordPath + '/execute', { headers, data: {} })))
  for (const r of repeats) { expect(r.status()).toBe(200); expect(await r.json()).toEqual(execution) }
  const retry = await request.post(`${aiBase}/api/meetings/${meetingId}/assignment/suggestions`, { headers, data: { ...saved.input, client_request_id: saved.client_request_id } })
  expect(retry.status()).toBe(200); expect((await retry.json()).id).toBe(saved.id)
  expect((await read(recordPath)).result).toEqual(saved.result)
  const logs = (await read('/api/stories/logs')).filter(l => l.detail.includes('会议分配建议执行'))
  expect(logs).toHaveLength(1); expect(logs[0].id).toBe(execution.story_log_id)
  expect((await request.post(base + recordPath + '/execute', { data: {} })).status()).toBe(401)
  const login = await request.post(base + '/api/auth/login', { data: { username: '成员5', password: '123456' } })
  const viewer = { Authorization: `Bearer ${(await login.json()).access_token}` }
  expect((await request.post(preparePath, { headers: viewer, data: { audio_id: meta.id } })).status()).toBe(403)
  expect((await request.post(base + recordPath + '/execute', { headers: viewer, data: {} })).status()).toBe(403)
  expect((await request.post(base + recordPath + '/review', { headers: viewer, data: done.review.input })).status()).toBe(403)
  expect((await request.post(`${aiBase}/api/meetings/${meetingId}/assignment/suggestions`, { headers: viewer, data: { ...saved.input, client_request_id: 'viewer-denied' } })).status()).toBe(403)
  await page.reload(); await page.locator('details').filter({ hasText: 'Assignment 分配建议' }).locator('summary').click()
  await expect(history).toContainText(`故事日志 #${execution.story_log_id}`)
  await expect(history.getByRole('button', { name: '执行已批准分配' })).toHaveCount(0)

  // Additional saved intents verify rejection and a real business change after approval.
  const create = async key => {
    const r = await request.post(`${aiBase}/api/meetings/${meetingId}/assignment/suggestions`, { headers, data: { ...saved.input, story_ids: ['US14'], client_request_id: key } })
    expect(r.status()).toBe(200); return r.json()
  }
  const rejected = await create('reject-intent')
  expect((await request.post(base + path + '/' + rejected.id + '/review', { headers, data: { decision: 'reject', member_id: null, reason: '暂不分配', capacity_acknowledged: false } })).status()).toBe(200)
  expect((await request.post(base + path + '/' + rejected.id + '/execute', { headers, data: {} })).status()).toBe(409)
  const stale = await create('stale-intent'), stalePath = path + '/' + stale.id
  const target = stale.result.candidates.find(c => c.member_id !== stale.result.context.selected_stories[0].owner_id)
  expect((await request.post(base + stalePath + '/review', { headers, data: { decision: 'approve', member_id: target.member_id, reason: '测试执行前重新核对', capacity_acknowledged: true } })).status()).toBe(200)
  expect((await request.patch(base + '/api/stories/US14', { headers, data: { title: '执行前已变化的故事' } })).status()).toBe(200)
  expect((await request.post(base + stalePath + '/execute', { headers, data: {} })).status()).toBe(409)
  expect((await read(stalePath)).review.execution_status).toBe('not_started')
  expect((await read('/api/stories/logs')).filter(l => l.detail.includes('会议分配建议执行'))).toHaveLength(1)
  expect((await request.delete(base + `/api/meetings/${meetingId}`, { headers })).status()).toBe(409)
})
