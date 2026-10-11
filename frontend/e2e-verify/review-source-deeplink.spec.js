import { test, expect } from '@playwright/test'

test('来源分析深链刷新后仍选中指定会议、类型和分析记录', async ({ page }) => {
  const meetingId = 'meeting-planning-1'
  const analysisId = 'analysis-planning-9'
  await page.addInitScript(() => {
    window.__AICAP_API_BASE__ = 'http://mock.local'
    localStorage.setItem('aiguanli_token', 'mock-token')
  })
  await page.route('http://mock.local/**', async route => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: {
        'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, OPTIONS',
        'access-control-allow-headers': 'authorization, content-type'
      } })
      return
    }
    const path = new URL(request.url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    else if (path === '/api/auth/me') body = { id: 1, username: 'owner', display_name: '负责人', role: 'owner' }
    else if (path === '/api/auth/users') body = [{ id: 1, username: 'owner', display_name: '负责人', role: 'owner' }]
    else if (path === '/api/meetings') body = [{ id: meetingId, title: 'Sprint 计划会', transcript: '我们准备调整下一轮 Sprint。' }]
    else if (path === `/api/meetings/${meetingId}/planning-analyses`) body = [{
      id: analysisId, client_request_id: 'planning-request-9', submitted_by: 1,
      created_at: '2026-10-09T01:20:00Z', transcript: '已确认的 Sprint 计划会议原文',
      result: { summary: '来源深链验收分析记录', open_questions: [] }, story_snapshots: []
    }]
    else if (path.endsWith('/proposal-reviews') && request.method() === 'GET') body = { review_status: 'no_changes', proposals: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', headers: {
      'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, OPTIONS',
      'access-control-allow-headers': 'authorization, content-type'
    }, body: JSON.stringify(body) })
  })

  await page.goto(`/#/meetings?meetingId=${meetingId}&type=planning&analysisId=${analysisId}&fromReview=1&status=pending&source=planning`)
  await expect(page.locator('#meeting-select')).toHaveValue(meetingId)
  await expect(page.getByLabel('会议分析类型')).toHaveValue('planning')
  await expect(page.getByLabel('Planning分析记录')).toHaveValue(analysisId)
  await expect(page.getByText('来源深链验收分析记录')).toBeVisible()
  await expect(page.getByRole('button', { name: '← 返回审核中心（保留筛选）' })).toBeVisible()

  await page.reload()
  await expect(page.locator('#meeting-select')).toHaveValue(meetingId)
  await expect(page.getByLabel('会议分析类型')).toHaveValue('planning')
  await expect(page.getByLabel('Planning分析记录')).toHaveValue(analysisId)
  await expect(page.getByText('来源深链验收分析记录')).toBeVisible()
})
