import { test, expect } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'

test('real speech version survives reload and confirmed text enters meeting analysis', async ({ page, request }) => {
  const language = process.env.AICAP_STT_EVAL_LANGUAGE || 'en'
  const expectedSpeakers = Number(process.env.AICAP_STT_EVAL_SPEAKERS || 1)
  const audioName = basename(process.env.AICAP_STT_EVAL_AUDIO)
  await page.goto('/#/ai')
  await page.locator('#login-user').fill('李锐铭')
  await page.locator('#login-pass').fill('123456')
  await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('#login')).not.toBeVisible()
  await page.locator('#meeting-save-form input[name=title]').fill('本地转写真实联调')
  await page.locator('#meeting-save-form textarea').fill('原会议文本，转写草稿不得覆盖。')
  await page.locator('#meeting-save-form button').click()
  await expect(page.locator('#saved-transcript')).toHaveText('原会议文本，转写草稿不得覆盖。')
  await page.locator('.recorder input[type=file]').setInputFiles(process.env.AICAP_STT_EVAL_AUDIO)
  const panel = page.getByRole('region', { name: `音频转写：${audioName}`, exact: true })
  await expect(panel).toBeVisible()
  await panel.getByLabel('转写语言').selectOption(language)
  const response = page.waitForResponse(r => r.url().endsWith('/transcription/run') && r.request().method() === 'POST')
  await panel.getByRole('button', { name: '转写此音频' }).click()
  const result = await response
  expect(result.status(), await result.text()).toBe(200)
  const data = await result.json()
  expect(data.status).toBe('draft'); expect(data.segments.length).toBeGreaterThan(0)
  expect(data.segments.every(segment=>Array.isArray(segment.words) && segment.words.length>0)).toBe(true)
  if(language === 'en') {
    expect(data.text.toLowerCase()).toContain('login')
    expect(data.text.toLowerCase()).toContain('human confirmation')
  } else expect(data.text.trim().length).toBeGreaterThan(0)
  expect(data.segments.every(s => s.speaker_id === null)).toBe(true)
  await expect(panel.getByRole('textbox')).toHaveValue(data.text)
  await expect(panel).toContainText('说话人未知')
  const speakerPanel = page.getByRole('region',{name:`说话人分离：${audioName}`,exact:true})
  await speakerPanel.getByLabel('预计人数').selectOption(String(expectedSpeakers))
  const speakerResponse = page.waitForResponse(r => r.url().endsWith('/diarization/run') && r.request().method() === 'POST')
  await speakerPanel.getByRole('button',{name:'分析说话人时间段'}).click()
  const speakerResult = await speakerResponse
  expect(speakerResult.status(),await speakerResult.text()).toBe(200)
  const speakerData = await speakerResult.json()
  expect(speakerData.requested_num_speakers).toBe(expectedSpeakers)
  expect(speakerData.speaker_count).toBe(expectedSpeakers)
  expect(speakerData.turns.length).toBeGreaterThan(0)
  expect([...new Set(speakerData.turns.map(turn=>turn.speaker_id))]).toEqual(Array.from({length:expectedSpeakers},(_,i)=>`SPK${i+1}`))
  await expect(speakerPanel).toContainText(`检测 ${expectedSpeakers} 位匿名说话人`)
  const meetingId = await page.locator('#meeting-select').inputValue()
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const saved = await request.get(`http://127.0.0.1:18180/api/meetings/${meetingId}`, { headers: { Authorization: `Bearer ${token}` } })
  expect((await saved.json()).transcript).toBe('原会议文本，转写草稿不得覆盖。')
  writeFileSync(join(process.env.AICAP_LIVE_ARTIFACT_DIR, 'transcription-draft.json'), JSON.stringify(data, null, 2))
  await panel.getByRole('button',{name:'保存转写版本'}).click()
  await expect(panel).toContainText('转写版本已保存，可刷新恢复。')
  const versionPath = `http://127.0.0.1:18180/api/meetings/${meetingId}/transcript-versions`
  const headers = { Authorization: `Bearer ${token}` }
  const versionDraft = { audio_id:data.audio.audio_id, sha256:data.audio.sha256, duration_ms:data.duration_ms, language:data.language, text:data.text, segments:data.segments,
    diarization:{engine:speakerData.engine,duration_ms:speakerData.duration_ms,requested_num_speakers:speakerData.requested_num_speakers,
      speaker_count:speakerData.speaker_count,turns:speakerData.turns,identity_status:speakerData.identity_status} }
  const stored = await (await request.get(versionPath,{headers})).json()
  expect(stored).toHaveLength(1)
  const version = stored[0]
  const saveInput = { client_request_id:version.client_request_id, draft:versionDraft }
  expect(await (await request.post(versionPath, { headers, data:saveInput })).json()).toEqual(version)
  await panel.getByRole('button',{name:'刷新转写历史'}).click()
  await expect(panel.getByLabel('人工核对文本')).toHaveValue(data.text)
  await page.reload()
  await expect(panel.getByLabel('人工核对文本')).toHaveValue(data.text)
  const speakersFor = interval => {
    let speakerId=null,bestOverlap=0; const overlappingSpeakers=[]
    for(const turn of speakerData.turns) {
      const overlap=Math.max(0,Math.min(interval.end_ms,turn.end_ms)-Math.max(interval.start_ms,turn.start_ms))
      if(overlap>0 && !overlappingSpeakers.includes(turn.speaker_id)) overlappingSpeakers.push(turn.speaker_id)
      if(overlap>bestOverlap) { bestOverlap=overlap; speakerId=turn.speaker_id }
    }
    return {speakerId,overlappingSpeakers}
  }
  const expectedAssignments=[]
  for(const segment of data.segments) {
    const groups=[]
    for(const word of segment.words) {
      const {speakerId}=speakersFor(word),last=groups.at(-1)
      if(last?.speaker_id===speakerId) { last.word_ids.push(word.word_id); last.text+=word.text; last.end_ms=word.end_ms }
      else groups.push({speaker_id:speakerId,word_ids:[word.word_id],text:word.text,start_ms:word.start_ms,end_ms:word.end_ms})
    }
    groups.forEach((group,index)=>expectedAssignments.push({assignment_id:`${segment.segment_id}A${index+1}`,segment_id:segment.segment_id,
      word_ids:group.word_ids,text:group.text,speaker_id:group.speaker_id,
      overlapping_speakers:speakersFor(group).overlappingSpeakers}))
  }
  expect(expectedAssignments.every(item=>item.speaker_id)).toBe(true)
  expect([...new Set(expectedAssignments.map(item=>item.speaker_id))].sort()).toEqual(
    Array.from({length:expectedSpeakers},(_,i)=>`SPK${i+1}`))
  for(const assignment of expectedAssignments)
    await expect(panel.getByLabel(new RegExp(`^${assignment.assignment_id} ·`))).toHaveValue(assignment.speaker_id || '')
  // Synthetic human correction exercises the existing Daily fixture, not STT semantic accuracy.
  const corrected = 'US13 今天开始开发。负责人和截止时间仍待确认。'
  await panel.getByLabel('人工核对文本').fill(corrected)
  await panel.getByLabel('我已对照录音核对文本，理解说话人未识别').check()
  await panel.getByRole('button',{name:'确认并创建分析会议'}).click()
  await expect(panel).toContainText('人工核对已保存，分析会议已创建。')
  const versions = await (await request.get(versionPath,{headers})).json()
  expect(versions).toHaveLength(1)
  const confirmation = versions[0].confirmation
  expect(versions[0].draft.text).toBe(data.text)
  expect(confirmation.input.text).toBe(corrected)
  expect(confirmation.input.speaker_alignment.audio_sha256).toBe(data.audio.sha256)
  expect(confirmation.input.speaker_alignment.alignment_version).toBe(3)
  expect(confirmation.input.speaker_alignment.speaker_count).toBe(expectedSpeakers)
  expect(versions[0].draft.diarization.turns).toEqual(speakerData.turns)
  expect(confirmation.input.speaker_alignment.turns).toEqual(speakerData.turns)
  expect(confirmation.input.speaker_alignment.assignments.length).toBeGreaterThanOrEqual(data.segments.length)
  expect(confirmation.input.speaker_alignment.assignments).toEqual(expectedAssignments)
  expect(await (await request.post(`${versionPath}/${version.id}/confirm`,{headers,data:confirmation.input})).json()).toEqual(versions[0])
  expect((await request.post(`${versionPath}/${version.id}/confirm`,{headers,data:{...confirmation.input,text:'其他文本'}})).status()).toBe(409)
  expect((await request.delete(`http://127.0.0.1:18180/api/audio/${data.audio.audio_id}`,{headers})).status()).toBe(409)
  for (const id of [meetingId,confirmation.analysis_meeting_id]) expect((await request.delete(`http://127.0.0.1:18180/api/meetings/${id}`,{headers})).status()).toBe(409)
  expect((await (await request.get(`http://127.0.0.1:18180/api/meetings/${meetingId}`,{headers})).json()).transcript).toBe('原会议文本，转写草稿不得覆盖。')
  await page.reload()
  await page.locator('#meeting-select').selectOption(meetingId)
  await panel.getByRole('button',{name:'打开分析会议'}).click()
  await expect(page.locator('#saved-transcript')).toHaveText(corrected)
  await page.getByRole('button',{name:'分析每日站会',exact:true}).click()
  await expect(page.getByRole('region',{name:'每日站会状态分析'}).getByRole('status')).toContainText('分析已保存',{timeout:20000})
  const analysisId = await page.getByLabel('状态分析记录').inputValue()
  const analysis = await (await request.get(`http://127.0.0.1:18180/api/meetings/${confirmation.analysis_meeting_id}/status-analyses/${analysisId}`,{headers})).json()
  expect(analysis.transcript).toBe(corrected)
  writeFileSync(join(process.env.AICAP_LIVE_ARTIFACT_DIR,'transcript-version.json'),JSON.stringify({version:versions[0],analysis},null,2))
  writeFileSync(join(process.env.AICAP_LIVE_ARTIFACT_DIR,'diarization-preview.json'),JSON.stringify({...speakerData,expected_assignments:expectedAssignments},null,2))
})
