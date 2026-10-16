import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { test, expect } from '@playwright/test'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'
const aiBase = process.env.AICAP_LIVE_AI_BASE || 'http://127.0.0.1:8090'
const projectId = 'P002'

test('Sprint 2 real local speech draft is revised and retained separately from source', async ({ request }) => {
  test.setTimeout(180000)
  const samplePath = process.env.AICAP_STT_EVAL_AUDIO
  expect(samplePath, 'Set AICAP_STT_EVAL_AUDIO to the existing local evaluation MP3').toBeTruthy()
  const sample = readFileSync(samplePath)
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const meeting = await request.post(`${javaBase}/api/meetings?projectId=${projectId}`, {
    headers, data: { project_id: projectId, title: `Sprint2本地语音联调 ${randomUUID().slice(0, 8)}`,
      transcript: '原会议文本保留，不被自动转写覆盖。' }
  })
  expect(meeting.status()).toBe(201)
  const meetingId = (await meeting.json()).id
  const uploaded = await request.post(`${javaBase}/api/meetings/${meetingId}/audio?projectId=${projectId}`, {
    headers, multipart: { file: { name: 'speech-eval.mp3', mimeType: 'audio/mpeg', buffer: sample },
      source: 'upload', consent_confirmed: 'true' }
  })
  expect(uploaded.status()).toBe(200)
  const audioId = (await uploaded.json()).id
  const transcribed = await request.post(`${aiBase}/api/meetings/${meetingId}/transcription/run?projectId=${projectId}`, {
    headers, data: { audio_id: audioId, language: 'en' }, timeout: 120000
  })
  expect(transcribed.status(), await transcribed.text()).toBe(200)
  const draft = await transcribed.json()
  expect(draft.status).toBe('draft')
  expect(draft.text.toLowerCase()).toContain('login')
  expect(draft.segments.length).toBeGreaterThan(0)
  expect(draft.segments.every(row => row.speaker_id === null && row.words?.length > 0)).toBe(true)
  const versionPath = `${javaBase}/api/meetings/${meetingId}/transcript-versions`
  const saved = await request.post(versionPath, { headers, data: { client_request_id: randomUUID(), draft: {
    audio_id: audioId, sha256: draft.audio.sha256, duration_ms: draft.duration_ms,
    language: draft.language, text: draft.text, segments: draft.segments
  } } })
  expect(saved.status(), await saved.text()).toBe(200)
  const version = await saved.json()
  const corrected = '人工核对：先完成登录接口，再补充测试；负责人和截止时间仍待确认。'
  const confirmed = await request.post(`${versionPath}/${version.id}/confirm`, {
    headers, data: { title: 'Sprint2语音人工核对分析会议', text: corrected, acknowledged: true }
  })
  expect(confirmed.status(), await confirmed.text()).toBe(200)
  const analysisId = (await confirmed.json()).confirmation.analysis_meeting_id
  const source = await request.get(`${javaBase}/api/meetings/${meetingId}?projectId=${projectId}`, { headers })
  const analysis = await request.get(`${javaBase}/api/meetings/${analysisId}?projectId=${projectId}`, { headers })
  expect((await source.json()).transcript).toBe('原会议文本保留，不被自动转写覆盖。')
  expect((await analysis.json()).transcript).toBe(corrected)
  expect((await (await request.get(versionPath, { headers })).json())[0].draft.text).toBe(draft.text)
  const artifactDir = process.env.AICAP_LIVE_ARTIFACT_DIR || 'test-results/live'
  mkdirSync(artifactDir, { recursive: true })
  writeFileSync(join(artifactDir, 'sprint2-real-stt-summary.json'), JSON.stringify({
    source_meeting_id: meetingId, analysis_meeting_id: analysisId, audio_id: audioId,
    engine: draft.engine, language: draft.language, duration_ms: draft.duration_ms,
    segment_count: draft.segments.length, draft_text: draft.text, corrected_text: corrected,
    evidence_type: 'local speech evaluation sample; not a live team recording'
  }, null, 2))
})
