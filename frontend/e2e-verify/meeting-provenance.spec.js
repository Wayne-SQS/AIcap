import { test, expect } from '@playwright/test'

test('语音来源链展示确认人与分析会议，并能打开独立分析会议', async ({ page }) => {
  const sourceId = 'meeting-original'
  const analysisId = 'meeting-analysis'
  const audioId = 'audio-source-1'
  const versionId = 'version-confirmed-1'
  const sha256 = 'a'.repeat(64)
  const meetings = [
    { id: sourceId, title: '来源会议：Sprint 计划会', transcript: '原始会议文本', created_by: 2, created_at: '2026-10-08T09:00:00Z' },
    { id: analysisId, title: '核对转写：Sprint 计划会', transcript: '已核对的会议内容', created_by: 2, created_at: '2026-10-08T10:00:00Z' }
  ]
  const audio = [{ id: audioId, meeting_id: sourceId, filename: 'sprint-plan.mp3', source: 'upload', byte_size: 4096, duration_ms: 1200, sha256 }]
  const version = {
    id: versionId, meeting_id: sourceId, audio_id: audioId, client_request_id: 'request-1',
    created_at: '2026-10-08T09:30:00Z', submitted_by: 2, provenance: 'caller_submitted',
    draft: { audio_id: audioId, sha256, duration_ms: 1200, language: 'zh', text: '未核对文本', segments: [{ segment_id: 'S1', start_ms: 0, end_ms: 1000, text: '未核对文本', speaker_id: null }] },
    confirmation: { input: { title: meetings[1].title, text: meetings[1].transcript, acknowledged: true, speaker_alignment: null }, confirmed_by: 3, confirmed_at: '2026-10-08T09:45:00Z', analysis_meeting_id: analysisId }
  }

  await page.addInitScript(() => {
    window.__AICAP_API_BASE__ = 'http://mock.local'
    localStorage.setItem('aiguanli_token', 'mock-token')
  })
  await page.route('http://mock.local/**', async route => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: {
        'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
        'access-control-allow-headers': 'authorization, content-type'
      } })
      return
    }
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    else if (path === '/api/auth/me') body = { id: 1, username: 'reviewer', display_name: '审核负责人', role: 'admin', capacity_hours: 60 }
    else if (path === '/api/auth/users') body = [{ id: 1, username: 'reviewer', display_name: '审核负责人', role: 'admin', capacity_hours: 60 }]
    else if (path === '/api/meetings') body = meetings
    else if (path === `/api/meetings/${sourceId}/audio`) body = audio
    else if (path === `/api/meetings/${sourceId}/transcript-versions`) body = [version]
    await route.fulfill({ status: 200, contentType: 'application/json', headers: {
      'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
      'access-control-allow-headers': 'authorization, content-type'
    }, body: JSON.stringify(body) })
  })

  await page.goto(`/#/meetings?meetingId=${sourceId}`)
  const meetingSelect = page.locator('#meeting-select')
  await expect(meetingSelect).toHaveValue(sourceId)
  await page.getByRole('tab', { name: '语音会议' }).click()
  const sourceChain = page.getByRole('region', { name: '语音会议来源链' })
  await expect(sourceChain).toBeVisible()
  await expect(page).toHaveURL(/workflow=audio/)
  await page.reload()
  await expect(page.getByRole('tab', { name: '语音会议' })).toHaveAttribute('aria-selected', 'true')
  const restoredSourceChain = page.getByRole('region', { name: '语音会议来源链' })
  await expect(restoredSourceChain).toBeVisible()
  await expect(restoredSourceChain).toContainText('来源会议：Sprint 计划会')
  await expect(restoredSourceChain).toContainText('sprint-plan.mp3')
  await expect(restoredSourceChain).toContainText(versionId)
  await expect(restoredSourceChain).toContainText('确认人 #3')
  await expect(restoredSourceChain).toContainText('核对转写：Sprint 计划会')
  await restoredSourceChain.getByRole('button', { name: '打开分析会议' }).click()
  await expect(meetingSelect).toHaveValue(analysisId)
  const audioTab = page.getByRole('tab', { name: '语音会议' })
  await audioTab.focus()
  await page.keyboard.press('ArrowRight')
  const manualTab = page.getByRole('tab', { name: '手动录入一条待审核建议' })
  await expect(manualTab).toHaveAttribute('aria-selected', 'true')
  await expect(manualTab).toBeFocused()
  await expect(page).toHaveURL(/workflow=manual/)
  await page.reload()
  await expect(page.getByRole('tab', { name: '手动录入一条待审核建议' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'meeting-workflow-tab-manual')
})

test('语音来源链确认失败后可原请求重试并生成独立分析会议', async ({ page }) => {
  const sourceId = 'meeting-source-retry'
  const analysisId = 'meeting-analysis-retry'
  const audioId = 'audio-retry-1'
  const versionId = 'version-retry-1'
  const sha256 = 'b'.repeat(64)
  let confirmed = false
  let confirmAttempts = []
  const sourceMeeting = { id: sourceId, title: '来源会议：迭代复盘', transcript: '原会议内容保持不变', created_by: 2, created_at: '2026-10-08T09:00:00Z' }
  const analysisMeeting = { id: analysisId, title: '核对转写：迭代复盘', transcript: '核对后的会议内容', created_by: 2, created_at: '2026-10-08T10:00:00Z' }
  const audio = [{ id: audioId, meeting_id: sourceId, filename: 'retro.mp3', source: 'upload', byte_size: 4096, duration_ms: 1200, sha256 }]
  const version = {
    id: versionId, meeting_id: sourceId, audio_id: audioId, client_request_id: 'request-retry-1',
    created_at: '2026-10-08T09:30:00Z', submitted_by: 2, provenance: 'caller_submitted',
    draft: { audio_id: audioId, sha256, duration_ms: 1200, language: 'zh', text: '待人工核对的会议内容', segments: [{ segment_id: 'S1', start_ms: 0, end_ms: 1000, text: '待人工核对的会议内容', speaker_id: null }] },
    confirmation: null
  }
  const headers = {
    'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
    'access-control-allow-headers': 'authorization, content-type'
  }
  await page.addInitScript(() => {
    window.__AICAP_API_BASE__ = 'http://mock.local'
    localStorage.setItem('aiguanli_token', 'mock-token')
  })
  await page.route('http://mock.local/**', async route => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers })
    const path = new URL(request.url()).pathname
    let body = []
    let status = 200
    if (path === '/api/health') body = { status: 'ok' }
    else if (path === '/api/auth/me') body = { id: 1, username: 'reviewer', display_name: '审核负责人', role: 'admin', capacity_hours: 60 }
    else if (path === '/api/auth/users') body = [{ id: 1, username: 'reviewer', display_name: '审核负责人', role: 'admin', capacity_hours: 60 }]
    else if (path === '/api/meetings') body = confirmed ? [sourceMeeting, analysisMeeting] : [sourceMeeting]
    else if (path === `/api/meetings/${sourceId}/audio`) body = audio
    else if (path === `/api/meetings/${sourceId}/transcript-versions` && request.method() === 'GET') body = confirmed ? [{ ...version, confirmation: confirmed }] : [version]
    else if (path === `/api/meetings/${sourceId}/transcript-versions/${versionId}/confirm`) {
      const submitted = request.postDataJSON()
      confirmAttempts.push(submitted)
      if (confirmAttempts.length === 1) {
        status = 503
        body = { detail: 'simulated connection loss' }
      } else {
        confirmed = {
          input: submitted, confirmed_by: 1, confirmed_at: '2026-10-08T11:00:00Z', analysis_meeting_id: analysisId
        }
        body = { ...version, confirmation: confirmed }
      }
    }
    return route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) })
  })

  await page.goto(`/#/meetings?meetingId=${sourceId}&workflow=audio`)
  await expect(page.getByRole('tab', { name: '语音会议' })).toHaveAttribute('aria-selected', 'true')
  const versions = page.getByRole('region', { name: '转写版本与人工核对' })
  await expect(versions.getByLabel('已保存转写版本')).toHaveValue(versionId)
  await versions.getByLabel('分析会议标题').fill('核对转写：迭代复盘')
  await versions.getByLabel('人工核对文本').fill('这是人工核对后的会议内容')
  await versions.getByLabel('我已对照录音核对文本，理解说话人未识别').check()
  await versions.getByRole('button', { name: '确认并创建分析会议' }).click()
  await expect(versions.getByRole('alert')).toContainText('确认结果待核对')
  await versions.getByRole('button', { name: '重试原确认提交' }).click()
  await expect(versions.getByRole('status')).toContainText('人工核对已保存，分析会议已创建。')
  expect(confirmAttempts).toHaveLength(2)
  expect(confirmAttempts[1]).toEqual(confirmAttempts[0])
  expect(confirmAttempts[1]).toMatchObject({ title: '核对转写：迭代复盘', text: '这是人工核对后的会议内容', acknowledged: true, speaker_alignment: null })
  await expect(versions).toContainText(analysisId)
  await expect(page.locator('#meeting-select')).toHaveValue(sourceId)
  await expect(page.locator('#saved-transcript')).toHaveText('原会议内容保持不变')
})
