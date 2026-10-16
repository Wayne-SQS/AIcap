import { createHash, randomUUID } from 'node:crypto'
import { test, expect } from '@playwright/test'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'
const projectId = 'P002'

test('Sprint 2 consent, transcript revision and retained analysis survive reload', async ({ browser, request }) => {
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const source = await request.post(`${javaBase}/api/meetings?projectId=${projectId}`, {
    headers, data: { project_id: projectId, title: `Sprint2转写留存联调 ${randomUUID().slice(0, 8)}`,
      transcript: '原会议原文，应与核对后的分析文本分别留存。' }
  })
  expect(source.status()).toBe(201)
  const meetingId = (await source.json()).id
  // This validates byte integrity and the human revision workflow. The small
  // MP3 fixture is not a speech-recognition accuracy sample.
  const audio = Buffer.alloc(2048)
  audio.set([0x49, 0x44, 0x33, 3], 0)
  audio.set([0xff, 0xfb, 0x90, 0x64], 10)
  const uploadPath = `${javaBase}/api/meetings/${meetingId}/audio?projectId=${projectId}`
  const file = { name: 'sprint2-qa.mp3', mimeType: 'audio/mpeg', buffer: audio }
  const denied = await request.post(uploadPath, {
    headers, multipart: { file, source: 'upload', duration_ms: '1000', consent_confirmed: 'false' }
  })
  expect(denied.status()).toBe(422)
  const uploaded = await request.post(uploadPath, {
    headers, multipart: { file, source: 'upload', duration_ms: '1000', consent_confirmed: 'true' }
  })
  expect(uploaded.status()).toBe(200)
  const meta = await uploaded.json()
  expect(meta.sha256).toBe(createHash('sha256').update(audio).digest('hex'))
  const versionPath = `${javaBase}/api/meetings/${meetingId}/transcript-versions`
  const draftText = '待人工核对的转写草稿。'
  const saved = await request.post(versionPath, {
    headers, data: { client_request_id: randomUUID(), draft: {
      audio_id: meta.id, sha256: meta.sha256, duration_ms: 1000, language: 'zh', text: draftText,
      segments: [{ segment_id: 'S1', start_ms: 0, end_ms: 900, text: draftText, speaker_id: null }]
    } }
  })
  expect(saved.status()).toBe(200)
  const version = await saved.json()
  expect(version.confirmation).toBeNull()
  const corrected = '人工核对：US12 的负责人变更已同步，截止日期仍待确认。'
  const confirmed = await request.post(`${versionPath}/${version.id}/confirm`, {
    headers, data: { title: 'Sprint2人工核对分析会议', text: corrected, acknowledged: true }
  })
  expect(confirmed.status()).toBe(200)
  const confirmation = (await confirmed.json()).confirmation
  const analysisId = confirmation.analysis_meeting_id
  expect(analysisId).toBeTruthy()
  const original = await request.get(`${javaBase}/api/meetings/${meetingId}?projectId=${projectId}`, { headers })
  expect((await original.json()).transcript).toBe('原会议原文，应与核对后的分析文本分别留存。')
  const analysis = await request.get(`${javaBase}/api/meetings/${analysisId}?projectId=${projectId}`, { headers })
  expect(analysis.status()).toBe(200)
  expect((await analysis.json()).transcript).toBe(corrected)
  const list = await request.get(`${javaBase}/api/meetings?projectId=${projectId}`, { headers })
  expect((await list.json()).some(row => row.id === analysisId)).toBe(true)
  const history = await request.get(versionPath, { headers })
  expect((await history.json()).find(row => row.id === version.id)).toMatchObject({
    draft: { text: draftText }, confirmation: { analysis_meeting_id: analysisId }
  })
  expect((await request.delete(`${javaBase}/api/audio/${meta.id}?projectId=${projectId}`, { headers })).status()).toBe(409)
  expect((await request.delete(`${javaBase}/api/meetings/${meetingId}?projectId=${projectId}`, { headers })).status()).toBe(409)
  expect((await request.delete(`${javaBase}/api/meetings/${analysisId}?projectId=${projectId}`, { headers })).status()).toBe(409)

  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    await page.goto('/#/projects')
    await page.locator('#login-user').fill('李锐铭')
    await page.locator('#login-pass').fill('123456')
    await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('#login')).not.toBeVisible()
    await page.getByRole('button', { name: '进入项目' }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
    await page.goto(`/#/projects/${projectId}/meetings`)
    await page.locator('#meeting-select').selectOption(analysisId)
    await expect(page.locator('#saved-transcript')).toHaveText(corrected)
    await page.reload()
    await page.locator('#meeting-select').selectOption(analysisId)
    await expect(page.locator('#saved-transcript')).toHaveText(corrected)
  } finally { await context.close() }
})
