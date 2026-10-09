import { test, expect } from '@playwright/test'

test('分页队列只读取当前页详情并保留下一页游标', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    const paths = []
    window.fetch = async input => {
      const path = new URL(typeof input === 'string' ? input : input.url).pathname
      paths.push(path)
      if (path === '/api/review-queue') return Response.json({ items: [
        { source: 'daily', meetingId: 'm1', meetingTitle: '测试会议', analysisId: 'a1', proposalId: 'p1',
          createdAt: '2026-10-08T10:00:00', status: 'pending', executionStatus: 'not_started' },
        { source: 'general', meetingId: 'm1', meetingTitle: '测试会议', analysisId: 'S9001', proposalId: 'S9001',
          createdAt: '2026-10-08T09:00:00', status: 'pending', executionStatus: 'not_started' }
      ], nextCursor: 'next-page' })
      if (path.endsWith('/status-analyses/a1')) return Response.json({ id: 'a1', created_at: '2026-10-08T10:00:00', transcript: '会议原文', result: { summary: '状态分析' } })
      if (path.endsWith('/proposal-reviews')) return Response.json({ proposals: [{ proposal_id: 'p1', status: 'pending', original_proposal: { story_id: 'US01', changes: { status: 1 } } }] })
      if (path === '/api/suggestions/S9001') return Response.json({ id: 'S9001', origin: 'manual', meeting_id: 'm1',
        meeting_title: '测试会议', created_at: '2026-10-08T09:00:00', status: 'pending', note: '手动建议', changes: {} })
      return Response.json({ detail: 'unexpected request' }, { status: 500 })
    }
    try {
      const { loadReviewQueuePage } = await import('/src/api/reviewQueue.js')
      const queue = await loadReviewQueuePage({ status: 'pending', source: 'all' }, null, 2)
      return { ...queue, paths }
    } finally { window.fetch = originalFetch }
  })
  expect(result.errors).toEqual([])
  expect(result.entries.map(row => row.id)).toEqual(['daily:m1:a1:p1', 'general:S9001'])
  expect(result.nextCursor).toBe('next-page')
  expect(result.paths).toHaveLength(4)
  expect(result.paths).not.toContain('/api/meetings/m1/status-analyses/a1/proposal-executions')
})

test('工作台摘要只读取汇总接口和最近一条提案详情', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    const paths = []
    window.fetch = async input => {
      const path = new URL(typeof input === 'string' ? input : input.url).pathname
      paths.push(path)
      if (path === '/api/review-queue/summary') return Response.json({
        pendingCount: 51, executionCount: 3, recentMeeting: { id: 'm1', title: '最新会议' },
        latest: { source: 'general', meetingId: 'm1', meetingTitle: '最新会议',
          analysisId: 'S9001', proposalId: 'S9001', createdAt: '2026-10-08T09:00:00',
          status: 'pending', executionStatus: 'not_started' }
      })
      if (path === '/api/suggestions/S9001') return Response.json({ id: 'S9001', origin: 'manual', meeting_id: 'm1',
        meeting_title: '最新会议', created_at: '2026-10-08T09:00:00', status: 'pending', note: '手动建议', changes: {} })
      return Response.json({ detail: 'unexpected request' }, { status: 500 })
    }
    try {
      const { loadReviewQueueSummary } = await import('/src/api/reviewQueue.js')
      return { ...await loadReviewQueueSummary(), paths }
    } finally { window.fetch = originalFetch }
  })
  expect(result.pendingCount).toBe(51)
  expect(result.executionCount).toBe(3)
  expect(result.latestEntry.id).toBe('general:S9001')
  expect(result.recentMeeting.title).toBe('最新会议')
  expect(result.errors).toEqual([])
  expect(result.paths).toEqual(['/api/review-queue/summary', '/api/suggestions/S9001'])
})

test('没有提案时摘要仍保留最近会议且不读取详情', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    const paths = []
    window.fetch = async input => {
      const path = new URL(typeof input === 'string' ? input : input.url).pathname
      paths.push(path)
      if (path === '/api/review-queue/summary') return Response.json({
        pendingCount: 0, executionCount: 0, latest: null,
        recentMeeting: { id: 'm1', title: '仅有会议' }
      })
      return Response.json({ detail: 'unexpected request' }, { status: 500 })
    }
    try {
      const { loadReviewQueueSummary } = await import('/src/api/reviewQueue.js')
      return { ...await loadReviewQueueSummary(), paths }
    } finally { window.fetch = originalFetch }
  })
  expect(result.latestEntry).toBeNull()
  expect(result.recentMeeting.title).toBe('仅有会议')
  expect(result.paths).toEqual(['/api/review-queue/summary'])
})

test('旧服务缺少摘要接口时保留 404 状态供页面回退', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    window.fetch = async () => Response.json({ detail: '路径不存在: api/review-queue/summary' }, { status: 404 })
    try {
      const { loadReviewQueueSummary } = await import('/src/api/reviewQueue.js')
      try { await loadReviewQueueSummary(); return null }
      catch (error) { return { status: error.status, message: error.message } }
    } finally { window.fetch = originalFetch }
  })
  expect(result).toEqual({ status: 404, message: '路径不存在: api/review-queue/summary' })
})
