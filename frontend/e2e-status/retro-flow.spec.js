import { test, expect } from '@playwright/test'

async function setup(page, role = 'admin', malformed = false) {
  let saved = null, review = null, execution = null
  const posts = []
  const proposal = { proposal_id: 'p1', action: 'create_action_item', changes: { title: '完善发布检查表', description: '补充检查步骤', owner_id: null, deadline_text: null }, reason: '会议决定', evidence: [{ segment_id: 'S1', quote: '决定完善发布检查表' }] }
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'fixture-token'))
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON(); posts.push(body)
    saved = { id: 'a1', client_request_id: body.client_request_id, submitted_by: 1, created_at: '2026-09-28', transcript: '决定完善发布检查表', member_snapshots: [], result: { summary: '改进发布流程', decisions: [{ text: '完善发布检查表', evidence: proposal.evidence }], open_questions: ['负责人和时间待确认'], proposed_actions: [proposal] } }
    await route.fulfill({ json: { ...saved.result, analysis_id: 'a1', meeting_id: 'm1', client_request_id: body.client_request_id, storage_status: 'pending' } })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let data = []
    if (path.endsWith('/health')) data = { status: 'ok' }
    if (path.endsWith('/auth/me')) data = { id: 1, role, display_name: role }
    if (path.endsWith('/agent/config')) data = { configured: false }
    if (path.endsWith('/meetings')) data = [{ id: 'm1', title: 'Retro', transcript: '决定完善发布检查表' }]
    if (path.endsWith('/members/profiles')) data = [{ user_id: 2, display_name: '李明' }]
    if (path.includes('retro-analyses')) {
      data = saved ? [saved] : []
      if (path.endsWith('proposal-reviews')) {
        if (request.method() === 'POST') {
          const body = request.postDataJSON(); posts.push(body)
          review = { ...body, status: body.decision === 'reject' ? 'rejected' : 'approved', original_proposal: proposal, approved_proposal: body.decision === 'reject' ? null : { ...proposal, changes: body.changes || proposal.changes }, approved_owner_name: body.changes?.owner_id === 2 ? '李明' : null, reviewed_by: 1, reviewed_at: '2026-09-28', execution_status: body.decision === 'reject' ? 'not_applicable' : 'not_started' }
          data = review
        } else data = { review_status: review ? 'reviewed' : 'pending', proposals: [review || { proposal_id: 'p1', original_proposal: proposal, status: 'pending', execution_status: 'not_started' }] }
      }
      if (path.endsWith('proposal-executions')) {
        if (request.method() === 'POST') {
          posts.push(request.postDataJSON())
          execution = { analysis_id: 'a1', proposal_id: 'p1', execution_status: 'succeeded', action_item_id: 'action-1', action_item_log_id: 42, executed_by: 1, executed_at: '2026-09-28' }
          data = malformed ? { execution_status: 'succeeded' } : execution
          if (malformed) execution = null
        } else data = execution ? [execution] : []
      }
    }
    if (path.endsWith('/action-items')) data = execution ? [{ id: 'action-1', ...review.approved_proposal.changes, status: 'open', created_by: 1, created_at: '2026-09-28' }] : []
    if (path.endsWith('/action-items/action-1/logs')) data = [{ id: 42, log_type: 'create', user_id: 1, created_at: '2026-09-28', approved_proposal_json: JSON.stringify(review.approved_proposal) }]
    await route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  await page.getByRole('button', { name: '打开会议 Sprint Retro' }).click()
  return posts
}
const panelOf = page => page.getByRole('region', { name: 'Retro行动提案', exact: true })
async function analyze(panel) { await panel.getByRole('button', { name: '分析Sprint Retro', exact: true }).click() }

test('Retro edit approve execute and reload business action audit', async ({ page }) => {
  const posts = await setup(page), panel = panelOf(page)
  await analyze(panel)
  await expect(panel).toContainText('会议决议')
  await expect(panel).toContainText('负责人：未确定 · 截止时间：未确定')
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('批准后的事项', { exact: true }).fill('补充发布回滚清单')
  await panel.getByLabel('批准后的负责人').selectOption('2')
  await panel.getByLabel('批准后的截止时间（留空表示未确定）').fill('下月底前')
  await panel.getByLabel('修改原因（必填）').fill('人工确认')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的事项：补充发布回滚清单')
  await expect(panel).toContainText('原始事项：完善发布检查表')
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('执行结果已确认')
  await expect(panel.getByRole('region', { name: '已创建行动项' })).toContainText('补充发布回滚清单')
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Retro' }).click()
  await panel.getByRole('button', { name: '查看行动项审计' }).click()
  await expect(panel.getByRole('region', { name: '已创建行动项' })).toContainText('审计 #42')
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
  expect(posts).toHaveLength(3)
  expect(posts[0]).toMatchObject({ meeting_type: 'sprint_retrospective', current_sprint: null })
  expect(posts[1].changes).toEqual({ title: '补充发布回滚清单', description: '补充检查步骤', owner_id: 2, deadline_text: '下月底前' })
  expect(posts[2]).toEqual({ proposal_id: 'p1' })
})

test('Retro malformed execution does not show success', async ({ page }) => {
  await setup(page, 'admin', true); const panel = panelOf(page)
  await analyze(panel)
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel).toContainText('未能确认执行结果')
  await expect(panel).not.toContainText('执行结果已确认')
})

test('Retro unchanged edit blocked and rejected proposal cannot execute', async ({ page }) => {
  const posts = await setup(page), panel = panelOf(page)
  await analyze(panel)
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('修改原因（必填）').fill('核对')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('请修改行动内容并填写修改原因')
  expect(posts).toHaveLength(1)
  await panel.getByLabel('审核决定').selectOption('reject')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('已拒绝')
  await expect(panel.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
  expect(posts[1]).not.toHaveProperty('changes')
})

test('Retro member can analyze but cannot review', async ({ page }) => {
  await setup(page, 'member'); const panel = panelOf(page)
  await analyze(panel)
  await expect(panel).toContainText('原始事项：完善发布检查表')
  await expect(panel.getByRole('button', { name: '提交审核决定' })).toHaveCount(0)
})

test('Retro viewer cannot initiate analysis', async ({ page }) => {
  await setup(page, 'viewer'); const panel = panelOf(page)
  await expect(panel.getByRole('button', { name: '分析Sprint Retro', exact: true })).toHaveCount(0)
  await expect(panel).toContainText('负责人、管理员或成员可发起分析')
})

test('Retro uncertain analysis survives reload with same request', async ({ page }) => {
  await setup(page); const attempts = [], panel = panelOf(page)
  await page.route('**/meeting-ai/api/**', async route => {
    attempts.push(route.request().postDataJSON())
    await route.fulfill({ status: 503, json: { detail: 'storage_outcome_unknown' } })
  })
  await analyze(panel)
  await expect(panel).toContainText('未能确认分析保存结果')
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Retro' }).click()
  await panel.getByRole('button', { name: '重试本次分析', exact: true }).click()
  await expect(panel).toContainText('未能确认分析保存结果')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]).toEqual(attempts[0])
})

test('Retro direct approval preserves unknown owner and deadline', async ({ page }) => {
  const posts = await setup(page), panel = panelOf(page)
  await analyze(panel)
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await panel.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel.getByRole('region', { name: '已创建行动项' })).toContainText('负责人：未确定 · 截止时间：未确定')
  expect(posts[1]).not.toHaveProperty('changes')
})

test('Retro failed member directory blocks edits and allows retry', async ({ page }) => {
  const posts = await setup(page), panel = panelOf(page)
  await page.route('**/api/members/profiles', route => route.fulfill({ status: 503, json: { detail: 'unavailable' } }))
  await page.reload()
  await page.getByRole('button', { name: '打开会议 Sprint Retro' }).click()
  await analyze(panel)
  await expect(panel).toContainText('成员目录加载失败')
  await panel.getByLabel('审核决定').selectOption('modify_and_approve')
  await panel.getByLabel('批准后的事项', { exact: true }).fill('新事项')
  await panel.getByLabel('修改原因（必填）').fill('人工补充')
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('成员目录必须加载成功')
  expect(posts).toHaveLength(1)
  await page.unroute('**/api/members/profiles')
  await panel.getByRole('button', { name: '重试成员目录' }).click()
  await expect(panel.getByLabel('批准后的负责人')).toBeEnabled()
  await panel.getByRole('button', { name: '提交审核决定' }).click()
  await expect(panel).toContainText('批准后的事项：新事项')
})
