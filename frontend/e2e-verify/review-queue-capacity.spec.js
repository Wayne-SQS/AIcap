import { test, expect } from '@playwright/test'

test('跨来源队列限制全局并发，待审核提案不读取执行记录', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    let active = 0, maxActive = 0, executionReads = 0
    window.fetch = async input => {
      active++
      maxActive = Math.max(maxActive, active)
      try {
        await new Promise(resolve => setTimeout(resolve, 8))
        const path = new URL(typeof input === 'string' ? input : input.url).pathname
        if (path.endsWith('/assignment-suggestions')) return Response.json([])
        if (path.endsWith('/proposal-reviews')) {
          const id = Number(path.match(/analysis-(\d+)/)?.[1])
          return Response.json({ proposals: [{ proposal_id: `proposal-${id}`, status: id % 2 ? 'pending' : 'approved' }] })
        }
        if (path.endsWith('/proposal-executions')) {
          executionReads++
          return Response.json([])
        }
        if (path.endsWith('-analyses')) return Response.json(Array.from({ length: 4 }, (_, id) => ({
          id: `analysis-${id}`, created_at: `2026-10-08T10:00:0${id}Z`, result: { summary: 'test' }
        })))
        return Response.json([])
      } finally { active-- }
    }
    try {
      const { loadReviewQueue } = await import('/src/api/reviewQueue.js')
      const queue = await loadReviewQueue([
        { id: 'meeting-1', title: 'Meeting 1' },
        { id: 'meeting-2', title: 'Meeting 2' }
      ])
      return { rowCount: queue.entries.length, errors: queue.errors, maxActive, executionReads,
        pendingCount: queue.entries.filter(row => row.status === 'pending').length }
    } finally { window.fetch = originalFetch }
  })
  expect(result.errors).toEqual([])
  expect(result.rowCount).toBe(40)
  expect(result.pendingCount).toBe(20)
  expect(result.executionReads).toBe(20)
  expect(result.maxActive).toBeLessThanOrEqual(6)
})

test('执行记录暂时不可用时仍显示待审核提案', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    window.fetch = async input => {
      const path = new URL(typeof input === 'string' ? input : input.url).pathname
      if (path.endsWith('/status-analyses')) return Response.json([{ id: 'analysis-1', created_at: '2026-10-08T10:00:00Z' }])
      if (path.endsWith('/proposal-reviews')) return Response.json({ proposals: [
        { proposal_id: 'pending-1', status: 'pending' },
        { proposal_id: 'approved-1', status: 'approved', execution_status: 'not_started' }
      ] })
      if (path.endsWith('/proposal-executions')) return Response.json({ detail: 'unavailable' }, { status: 503 })
      return Response.json([])
    }
    try {
      const { loadReviewQueue } = await import('/src/api/reviewQueue.js')
      return await loadReviewQueue([{ id: 'meeting-1', title: 'Meeting 1' }])
    } finally { window.fetch = originalFetch }
  })
  expect(result.entries.map(row => row.proposalId).sort()).toEqual(['approved-1', 'pending-1'])
  expect(result.entries.find(row => row.proposalId === 'pending-1').status).toBe('pending')
  expect(result.errors).toHaveLength(1)
  expect(result.errors[0].source).toContain('执行记录')
})
