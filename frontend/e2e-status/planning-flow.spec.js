import { test, expect } from '@playwright/test'

async function setup(page, role = 'admin', malformed = false) {
  let saved = null, review = null, execution = null
  const posts = []
  const proposal = { proposal_id: 'p1', story_id: 'US13', expected: { sprint: 2 }, changes: { sprint: 3 }, reason: '会议决定', evidence: [{ segment_id: 'S1', quote: '调整到Sprint 3' }] }
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'fixture-token'))
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON()
    posts.push(body)
    saved = { id: 'a1', client_request_id: body.client_request_id, submitted_by: 1, created_at: '2026-09-27', transcript: '调整到Sprint 3', story_snapshots: [{ id: 'US13', title: '登录' }], result: { summary: '计划调整', open_questions: [], proposed_actions: [proposal] } }
    return route.fulfill({ json: { ...saved.result, analysis_id: 'a1', meeting_id: 'm1', client_request_id: body.client_request_id, storage_status: 'pending' } })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let data = []
    if (path.endsWith('/health')) data = { status: 'ok' }
    if (path.endsWith('/auth/me')) data = { id: 1, role, display_name: role }
    if (path.endsWith('/agent/config')) data = { configured: false }
    if (path.endsWith('/meetings')) data = [{ id: 'm1', title: 'Planning', transcript: '调整到Sprint 3' }]
    if (path.includes('planning-analyses')) {
      data = saved ? [saved] : []
      if (path.endsWith('proposal-reviews')) {
        if (request.method() === 'POST') {
          const body = request.postDataJSON(); posts.push(body)
          review = { ...body, status: body.decision === 'reject' ? 'rejected' : 'approved', original_proposal: proposal,
            approved_proposal: body.decision === 'reject' ? null : { ...proposal, changes: body.changes || proposal.changes }, execution_status: 'not_started' }
          data = review
        } else data = { review_status: review ? 'reviewed' : 'pending', proposals: [review || { proposal_id: 'p1', original_proposal: proposal, status: 'pending', execution_status: 'not_started' }] }
      }
      if (path.endsWith('proposal-executions')) {
        if (request.method() === 'POST') {
          posts.push(request.postDataJSON())
          execution = { analysis_id: 'a1', proposal_id: 'p1', story_id: 'US13', execution_status: 'succeeded', previous_sprint: 2, new_sprint: review.approved_proposal.changes.sprint, executed_by: 1, executed_at: '2026-09-27', story_log_id: 42 }
          data = malformed ? { execution_status: 'succeeded' } : execution
          if (malformed) execution = null
        } else data = execution ? [execution] : []
      }
    }
    await route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  await page.getByRole('button', { name: '打开会议 Sprint Planning' }).click()
  return posts
}

test('Planning analyze modify approve execute and refresh persists audit display', async ({ page }) => {
  const posts = await setup(page)
  const panel = page.getByRole('region', { name: '故事Sprint提案', exact: true })
  await panel.getByLabel('目标 Sprint（可选）').selectOption('3')
  await panel.getByRole('button', { name: '分析Sprint Planning', exact: true }).click()
  await expect(panel).toContainText('原始提案：Sprint 2 → Sprint 3')
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('批准后的目标Sprint').selectOption('4')
  await panel.getByLabel('修改原因（必填）').fill('依赖排期人工确认')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的变更：Sprint 2 → Sprint 4')
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('执行结果：Sprint 2 → Sprint 4')
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Planning' }).click()
  await expect(panel).toContainText('故事日志 #42')
  expect(posts).toHaveLength(3)
  expect(posts[0]).toMatchObject({ meeting_type: 'sprint_planning', target_sprint: 3, current_sprint: null })
  expect(posts[1].changes).toEqual({ sprint: 4 })
  expect(posts[2]).toEqual({ proposal_id: 'p1' })
})

test('Planning rejects malformed execution confirmation', async ({ page }) => {
  await setup(page, 'admin', true)
  const panel = page.getByRole('region', { name: '故事Sprint提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Planning', exact: true }).click()
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('未能确认执行结果')
  await expect(panel).not.toContainText('执行结果已确认')
})

test('Planning member can analyze but cannot review or execute', async ({ page }) => {
  await setup(page, 'member')
  const panel = page.getByRole('region', { name: '故事Sprint提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Planning', exact: true }).click()
  await expect(panel).toContainText('原始提案：Sprint 2 → Sprint 3')
  await expect(panel.getByRole('button', { name: '提交审核决定' })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
})
