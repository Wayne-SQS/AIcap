import { test, expect } from '@playwright/test'

async function setup(page, role = 'admin', malformed = false) {
  let saved = null, review = null, execution = null
  const posts = []
  const proposal = { proposal_id: 'p1', story_id: 'US13', expected: { status: 1 }, changes: { status: 2 }, reason: '会议决定', evidence: [{ segment_id: 'S1', quote: 'US13已验收通过' }] }
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'fixture-token'))
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON()
    posts.push(body)
    saved = { id: 'a1', client_request_id: body.client_request_id, submitted_by: 1, created_at: '2026-09-27', transcript: 'US13已验收通过', story_snapshots: [{ id: 'US13', title: '登录' }], result: { summary: '计划调整', open_questions: [], proposed_actions: [proposal] } }
    return route.fulfill({ json: { ...saved.result, analysis_id: 'a1', meeting_id: 'm1', client_request_id: body.client_request_id, storage_status: 'pending' } })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let data = []
    if (path.endsWith('/health')) data = { status: 'ok' }
    if (path.endsWith('/auth/me')) data = { id: 1, role, display_name: role }
    if (path.endsWith('/agent/config')) data = { configured: false }
    if (path.endsWith('/meetings')) data = [{ id: 'm1', title: 'Review', transcript: 'US13已验收通过' }]
    if (path.includes('review-analyses')) {
      data = saved ? [saved] : []
      if (path.endsWith('proposal-reviews')) {
        if (request.method() === 'POST') {
          const body = request.postDataJSON(); posts.push(body)
          review = { ...body, status: body.decision === 'reject' ? 'rejected' : 'approved', original_proposal: proposal,
            approved_proposal: body.decision === 'reject' ? null : { ...proposal, reason: body.changes?.reason || proposal.reason }, execution_status: 'not_started' }
          data = review
        } else data = { review_status: review ? 'reviewed' : 'pending', proposals: [review || { proposal_id: 'p1', original_proposal: proposal, status: 'pending', execution_status: 'not_started' }] }
      }
      if (path.endsWith('proposal-executions')) {
        if (request.method() === 'POST') {
          posts.push(request.postDataJSON())
          execution = { analysis_id: 'a1', proposal_id: 'p1', story_id: 'US13', execution_status: 'succeeded', previous_status: 1, new_status: 2, executed_by: 1, executed_at: '2026-09-27', story_log_id: 42 }
          data = malformed ? { execution_status: 'succeeded' } : execution
          if (malformed) execution = null
        } else data = execution ? [execution] : []
      }
    }
    await route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  await page.getByRole('button', { name: '打开会议 Sprint Review' }).click()
  return posts
}

test('Review analyze modify approve execute and refresh persists audit display', async ({ page }) => {
  const posts = await setup(page)
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })

  await panel.getByRole('button', { name: '分析Sprint Review', exact: true }).click()
  await expect(panel).toContainText('原始提案：进行中 → 已完成')
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('批准后的提案理由').fill('人工核对整体验收通过')
  await panel.getByLabel('修改原因（必填）').fill('依赖排期人工确认')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的提案理由：人工核对整体验收通过')
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('执行结果：进行中 → 已完成')
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Review' }).click()
  await expect(panel).toContainText('故事日志 #42')
  expect(posts).toHaveLength(3)
  expect(posts[0]).toMatchObject({ meeting_type: 'sprint_review', current_sprint: null })
  expect(posts[1].changes).toEqual({ reason: '人工核对整体验收通过' })
  expect(posts[2]).toEqual({ proposal_id: 'p1' })
})

test('Review rejects malformed execution confirmation', async ({ page }) => {
  await setup(page, 'admin', true)
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Review', exact: true }).click()
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('未能确认执行结果')
  await expect(panel).not.toContainText('执行结果已确认')
})

test('Review member can analyze but cannot review or execute', async ({ page }) => {
  await setup(page, 'member')
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Review', exact: true }).click()
  await expect(panel).toContainText('原始提案：进行中 → 已完成')
  await expect(panel.getByRole('button', { name: '提交审核决定' })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
})

test('Review rejects unchanged amendment and rejected proposal cannot execute', async ({ page }) => {
  const posts = await setup(page)
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Review', exact: true }).click()
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('修改原因（必填）').fill('核对')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('请修订提案理由并填写修改原因')
  expect(posts).toHaveLength(1)
  await panel.getByLabel('审核决定').selectOption('reject')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('已拒绝')
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
  expect(posts[1]).not.toHaveProperty('changes')
})

test('Review viewer cannot initiate analysis', async ({ page }) => {
  await setup(page, 'viewer')
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })
  await expect(panel.getByRole('button', { name: '分析Sprint Review', exact: true })).toHaveCount(0)
  await expect(panel).toContainText('负责人、管理员或成员可发起分析')
})

test('Review uncertain analysis retries same request after reload', async ({ page }) => {
  await setup(page)
  const attempts = []
  await page.route('**/meeting-ai/api/**', async route => {
    attempts.push(route.request().postDataJSON())
    await route.fulfill({ status: 503, json: { detail: 'storage_outcome_unknown' } })
  })
  const panel = page.getByRole('region', { name: 'Review完成提案', exact: true })
  await panel.getByRole('button', { name: '分析Sprint Review', exact: true }).click()
  await expect(panel).toContainText('未能确认分析保存结果')
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Review' }).click()
  await panel.getByRole('button', { name: '重试本次分析', exact: true }).click()
  await expect(panel).toContainText('未能确认分析保存结果')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]).toEqual(attempts[0])
})
