import { test, expect } from '@playwright/test'

test('跨来源队列汇总五类会议提案，并保留其它来源的部分结果', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const originalFetch = window.fetch
    window.__AICAP_API_BASE__ = 'http://mock.local'
    const keys = ['status', 'planning', 'review', 'retro', 'refinement']
    window.fetch = async input => {
      const url = new URL(typeof input === 'string' ? input : input.url)
      const path = url.pathname
      const failed = path.endsWith('/retro-analyses')
      if (failed) return new Response(JSON.stringify({ detail: 'simulated unavailable' }), { status: 503 })
      if (path.endsWith('/assignment-suggestions')) {
        const requirement = { dimension: 'tech_stack', name: 'Python', minimum_level: 3 }
        return Response.json([{
          id: 'assignment-1', meeting_id: 'meeting-1', client_request_id: 'assign-request-1',
          submitted_by: 1, created_at: '2026-10-08T10:30:00Z',
          input: { story_ids: ['US13'], target_sprint: 2, requirements: [requirement] },
          result: {
            rule_version: 'assignment-skills-v1', status: 'provisional',
            requirements_source: 'caller_supplied', writes_performed: false,
            context: {
              meeting_id: 'meeting-1', target_sprint: 2, scope: 'read_only_preparation',
              snapshot_consistency: 'sequential_reads', selected_stories: [{ id: 'US13', title: '审查看板体验', owner_id: 1 }],
              members: [{ profile: { user_id: 7, role: 'member' }, owned_story_ids: [], active_task_ids: [], target_sprint_task_ids: null }],
              gaps: [], tasks: []
            },
            candidates: [{ member_id: 7, display_name: '林晓', rank: 1, matched_requirements: 1, total_requirements: 1,
              matches: [{ requirement, recorded_level: 4, meets_requirement: true }],
              capacity_check: 'unknown', suitability: 'requires_human_review' }],
            excluded_viewer_ids: []
          },
          review: {
            status: 'approved', reviewed_by: 12, reviewed_at: '2026-10-08T11:30:00Z', execution_status: 'succeeded',
            input: { decision: 'approve', member_id: 7, reason: 'confirmed', capacity_acknowledged: true },
            execution: { suggestion_id: 'assignment-1', meeting_id: 'meeting-1', story_id: 'US13',
              execution_status: 'succeeded', previous_owner_id: 1, new_owner_id: 7,
              story_log_id: 78, executed_by: 13, executed_at: '2026-10-08T12:30:00Z' }
          }
        }])
      }
      if (path.endsWith('/proposal-reviews')) {
        const source = keys.find(key => path.includes(`/${key}-analyses/`))
        const originalProposal = source === 'planning'
          ? { reason: 'move to sprint 3', story_id: 42, expected: { sprint: 1 }, changes: { sprint: 3 }, evidence: [{ segment_id: 'S1', quote: 'source evidence' }] }
          : source === 'review'
            ? { reason: 'accepted after review', story_id: 42, expected: { status: 1 }, changes: { status: 2 }, evidence: [{ segment_id: 'S1', quote: 'source evidence' }] }
            : { reason: 'from todo to doing', story_id: 42, expected: { status: 0 }, changes: { status: 1 }, evidence: [{ segment_id: 'S1', quote: 'source evidence' }] }
        return Response.json({ review_status: 'pending', proposals: [{
          proposal_id: 'proposal-1', status: 'approved', execution_status: 'succeeded', decision: 'approved',
          reviewed_by: 12, reviewed_at: '2026-10-08T11:00:00Z', reason: 'confirmed',
          original_proposal: originalProposal
        }] })
      }
      if (path.endsWith('/proposal-executions')) return Response.json([{ proposal_id: 'proposal-1', execution_status: 'succeeded', executed_by: 13, executed_at: '2026-10-08T12:00:00Z', story_log_id: 77 }])
      const type = keys.find(key => path.endsWith(`/${key === 'status' ? 'status' : key}-analyses`))
      if (type) return Response.json([{
        id: `analysis-${type}`, created_at: '2026-10-08T10:00:00Z', submitted_by: 1,
        transcript: 'source transcript', result: { summary: `${type} summary` }
      }])
      return Response.json([])
    }
    try {
      const { loadReviewQueue } = await import('/src/api/reviewQueue.js')
      return await loadReviewQueue([{ id: 'meeting-1', title: 'Queue smoke meeting', transcript: 'meeting transcript' }])
    } finally { window.fetch = originalFetch }
  })
  expect(result.entries.map(row => row.source).sort()).toEqual(['assignment', 'daily', 'planning', 'refinement', 'review'])
  expect(result.entries.every(row => row.meetingTitle === 'Queue smoke meeting' && row.status === 'approved')).toBe(true)
  expect(result.entries.every(row => row.summaryText.length > 0)).toBe(true)
  expect(result.entries.find(row => row.source === 'daily').summaryText).toContain('需求 #42 · 待办 → 进行中')
  expect(result.entries.find(row => row.source === 'review').summaryText).toContain('需求 #42 · 进行中 → 已完成')
  expect(result.entries.find(row => row.source === 'planning').summaryText).toContain('Sprint 1 → 3')
  expect(result.entries.find(row => row.source === 'assignment').summaryText).toContain('故事 US13 · 审查看板体验 · 目标 Sprint 2 · 候选 林晓 1/1 · 容量未验证')
  expect(result.entries.filter(row => row.source !== 'assignment').every(row => row.reviewer === 12 && row.executor === 13 && row.resultId === 77)).toBe(true)
  const assignment = result.entries.find(row => row.source === 'assignment')
  expect(assignment).toMatchObject({ reviewer: 12, executor: 13, resultId: 78 })
  expect(result.entries.every(row => row.flowStage === '分析完成 → 已批准 → 执行成功')).toBe(true)
  expect(result.errors).toHaveLength(1)
  expect(result.errors[0].source).toBe('Sprint Retro')
})
