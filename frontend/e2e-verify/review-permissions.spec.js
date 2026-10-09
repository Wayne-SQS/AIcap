import { test, expect } from '@playwright/test'

test('提案队列只读权限：查看者可查看但没有审核写操作', async ({ page }) => {
  const writes = []
  let suggestionStatus = 'pending'
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'mock-token'))
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (request.method() !== 'GET') writes.push(`${request.method()} ${pathname}`)

    let body = []
    if (pathname === '/api/health') body = { status: 'ok' }
    else if (pathname === '/api/auth/me') body = { id: 8, username: 'reader', display_name: '只读成员', role: 'viewer' }
    else if (pathname === '/api/auth/users') body = [{ id: 8, username: 'reader', display_name: '只读成员', role: 'viewer' }]
    else if (pathname === '/api/meetings') body = [{ id: 'meeting-1', title: '权限检查会议', transcript: '我们要完善评审流程。' }]
    else if (pathname === '/api/suggestions') body = [{
      id: 'suggestion-1', meeting_id: 'meeting-1', meeting_title: '权限检查会议',
      status: suggestionStatus, origin: 'manual', evidence: '完善评审流程', note: '确认后再开发',
      changes: { title: '改进评审流程', priority: 'Should', description: '增加步骤状态' },
      ...(suggestionStatus === 'rejected' ? { reviewed_by: 1, reviewed_at: '2026-10-09T02:00:00Z', reason: '本轮暂不处理' } : {})
    }]
    else if (pathname === '/api/suggestions/suggestion-1/review' && request.method() === 'POST') {
      suggestionStatus = 'rejected'
      body = {
        id: 'suggestion-1', meeting_id: 'meeting-1', meeting_title: '权限检查会议', status: 'rejected', origin: 'manual',
        evidence: '完善评审流程', note: '确认后再开发', changes: { title: '改进评审流程', priority: 'Should', description: '增加步骤状态' },
        reviewed_by: 1, reviewed_at: '2026-10-09T02:00:00Z', reason: JSON.parse(request.postData() || '{}').reason
      }
    }
    else if (pathname.endsWith('/status-analyses')) body = [{
      id: 'analysis-1', created_at: '2026-10-09T01:00:00Z',
      transcript: '我们要完善评审流程。', result: { summary: '评审流程改进' }
    }]
    else if (pathname.endsWith('/proposal-reviews') && request.method() === 'GET') body = {
      review_status: 'pending', proposals: [{
        proposal_id: 'status-proposal-1', status: 'pending',
        original_proposal: { reason: '完善评审流程', evidence: [{ quote: '完善评审流程' }], changes: { story_id: 'US01', status: 1 } }
      }]
    }

    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/#/review')
  await expect(page.getByRole('heading', { name: '提案审核中心' })).toBeVisible()
  await expect(page.getByText('当前账号可查看提案记录；审核与执行仅管理员或负责人可以操作。')).toBeVisible()
  await expect(page.locator('.queue-card').first()).toContainText('需求 #US01')
  await expect(page.getByRole('button', { name: '查看来源分析与处理记录' })).toHaveCount(1)
  await expect(page.getByRole('button', { name: '查看来源会议' })).toHaveCount(1)
  await expect(page.locator('.review-queue .sug-acts')).toHaveCount(0)
  await expect(page.locator('[data-meeting-suggestion="suggestion-1"] .sug-acts')).toHaveCount(0)
  await expect(page.locator('.sug-card:not(.queue-card)[data-meeting-suggestion="suggestion-1"]')).toHaveCount(0)
  const mobileLayout = await page.locator('.review-queue').evaluate(section => ({
    width: section.clientWidth,
    scrollWidth: section.scrollWidth,
    filterColumns: getComputedStyle(section.querySelector('.queue-filters')).gridTemplateColumns.trim().split(/\s+/).length,
    buttonHeight: section.querySelector('button').getBoundingClientRect().height
  }))
  expect(mobileLayout.scrollWidth).toBeLessThanOrEqual(mobileLayout.width + 1)
  expect(mobileLayout.filterColumns).toBe(1)
  expect(mobileLayout.buttonHeight).toBeGreaterThanOrEqual(38)

  await page.evaluate(() => { window.sessionStore.currentUser = { id: 9, username: 'member', display_name: '普通成员', role: 'member' } })
  await expect(page.locator('.queue-permission')).toBeVisible()
  await expect(page.locator('.review-queue .sug-acts')).toHaveCount(0)
  await expect(page.locator('[data-meeting-suggestion="suggestion-1"] .sug-acts')).toHaveCount(0)

  await page.evaluate(() => { window.sessionStore.currentUser = { id: 1, username: 'owner', display_name: '负责人', role: 'owner' } })
  const generalSuggestion = page.locator('[data-meeting-suggestion="suggestion-1"]')
  await expect(generalSuggestion.locator('.sug-acts')).toBeVisible()
  await expect(page.locator('.sug-card:not(.queue-card)[data-meeting-suggestion="suggestion-1"]')).toHaveCount(0)
  await page.getByLabel('处理状态').selectOption('all')
  page.once('dialog', dialog => dialog.accept('本轮暂不处理'))
  await generalSuggestion.getByRole('button', { name: '拒绝' }).click()
  await expect(generalSuggestion).toContainText('已拒绝')
  expect(writes).toEqual(['POST /api/suggestions/suggestion-1/review'])

  await generalSuggestion.getByRole('button', { name: '查看来源会议' }).click()
  await expect(page.getByText('通用会议建议', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '← 返回审核中心（保留筛选）' }).click()
  await expect(page.getByLabel('处理状态')).toHaveValue('all')
  await expect(page.locator('[data-meeting-suggestion="suggestion-1"]')).toContainText('已拒绝')
})
