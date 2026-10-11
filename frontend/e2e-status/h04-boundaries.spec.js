import { test, expect } from '@playwright/test'

/*
 * Accepted H04 regressions. All business requests use HTTP fixtures.
 */

const proposal = (id = 'p1', story = 'US13') => ({
  proposal_id: id,
  story_id: story,
  expected: { status: 1 },
  changes: { status: 2 },
  reason: '验收已完成',
  evidence: [{ segment_id: 'S1', quote: '原文证据' }]
})

const analysis = (id = 'a1', story = 'US13') => ({
  id,
  client_request_id: id,
  submitted_by: 1,
  created_at: '2026-09-26 20:00:00',
  status: 'pending',
  transcript: `${story} 已完成全部验收。`,
  story_snapshots: [{ id: story, title: `故事 ${story}` }],
  result: {
    summary: `分析摘要 ${id}`,
    open_questions: [],
    proposed_actions: [proposal(`p-${id}`, story)]
  }
})

const pendingReview = (id = 'p-a1', story = 'US13') => ({
  proposal_id: id,
  original_proposal: proposal(id, story),
  approved_proposal: null,
  status: 'pending',
  decision: null,
  reason: null,
  reviewed_by: null,
  reviewed_at: null,
  execution_status: 'not_started'
})

const approvedReview = (id = 'p-a1', story = 'US13') => ({
  ...pendingReview(id, story),
  approved_proposal: proposal(id, story),
  status: 'approved',
  decision: 'approve',
  reason: '',
  reviewed_by: 1,
  reviewed_at: '2026-09-26 20:01:00'
})

async function setup(page, resolveStatus, {
  role = 'admin',
  blockAnalysisSessionStorage = false
} = {}) {
  const statusRequests = []
  await page.addInitScript(({ block }) => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    if (!block) return
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (this === window.sessionStorage && String(key).startsWith('aicap-daily-analysis:')) {
        throw new DOMException('fixture quota failure', 'QuotaExceededError')
      }
      return original.call(this, key, value)
    }
  }, { block: blockAnalysisSessionStorage })

  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.includes('status-analyses')) {
      statusRequests.push({ path, method: request.method(), body: request.postDataJSON?.() })
      const value = await resolveStatus(path, request)
      if (value?.status) {
        return route.fulfill({ status: value.status, json: value.body || { detail: 'fixture failure' } })
      }
      return route.fulfill({ json: value })
    }
    let data = []
    if (path === '/api/health') data = { status: 'ok' }
    if (path === '/api/auth/me') data = { id: 1, role, display_name: role }
    if (path === '/api/agent/config') data = { configured: false }
    if (path === '/api/meetings') data = [
      { id: 'm1', title: '站会一', transcript: '原文一' },
      { id: 'm2', title: '站会二', transcript: '原文二' }
    ]
    if (path === '/api/stories' || path === '/api/stories/logs') data = []
    return route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  return statusRequests
}

test('late detail from another analysis in the same meeting cannot replace the selected record', async ({ page }) => {
  let releaseA1
  let startedA1
  const a1Started = new Promise(resolve => { startedA1 = resolve })
  const a1Gate = new Promise(resolve => { releaseA1 = resolve })

  await setup(page, async path => {
    if (!path.includes('/a1/') && !path.includes('/a2/')) return [analysis('a1', 'US13'), analysis('a2', 'US14')]
    if (path.endsWith('proposal-executions')) return []
    if (path.includes('/a1/proposal-reviews')) {
      startedA1()
      await a1Gate
      return { review_status: 'pending', proposals: [pendingReview('p-a1', 'US13')] }
    }
    return { review_status: 'pending', proposals: [pendingReview('p-a2', 'US14')] }
  })

  await a1Started
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await page.getByLabel('状态分析记录').selectOption('a2')
  await expect(panel.locator('article')).toContainText('US14')

  const staleResponse = page.waitForResponse(response => response.url().includes('/a1/proposal-reviews'))
  releaseA1()
  await staleResponse
  await expect(panel.locator('article')).toContainText('US14')
  await expect(panel.locator('article')).not.toContainText('US13')
})

for (const [label, response] of [
  ['missing audit', { analysis_id: 'a1', proposal_id: 'p-a1', execution_status: 'succeeded' }],
  ...[
    ['wrong analysis', { analysis_id: 'other' }],
    ['wrong proposal', { proposal_id: 'other' }],
    ['invalid status', { new_status: true }],
    ['invalid audit id', { story_log_id: '42' }],
    ['blank execution time', { executed_at: ' ' }],
    ['unchanged status', { new_status: 1 }],
  ].map(([label, patch]) => [label, {
    analysis_id: 'a1', proposal_id: 'p-a1', execution_status: 'succeeded',
    story_id: 'US13', previous_status: 1, new_status: 2,
    story_log_id: 42, executed_by: 1, executed_at: '2026-09-26 20:02:00', ...patch
  }])
]) {
test(`malformed execution (${label}) is not presented as durable success`, async ({ page }) => {
  await setup(page, (path, request) => {
    if (request.method() === 'POST') {
      return response
    }
    if (path.endsWith('proposal-reviews')) {
      return { review_status: 'reviewed', proposals: [approvedReview()] }
    }
    if (path.endsWith('proposal-executions')) return []
    return [analysis()]
  })

  const panel = page.getByRole('region', { name: '故事状态提案' })
  await page.getByRole('button', { name: '执行已批准变更' }).click()

  await expect(panel).not.toContainText('执行结果已确认')
  await expect(panel).not.toContainText('故事日志 #undefined')
  await expect(panel).toContainText('未能确认执行结果')
  await expect(page.getByRole('button', { name: '执行已批准变更' })).toBeVisible()
})
}

test('analysis is not sent when its retry id cannot be persisted', async ({ page }) => {
  let analysisPosts = 0
  await setup(page, path => {
    if (path.endsWith('proposal-reviews')) return { review_status: 'no_changes', proposals: [] }
    if (path.endsWith('proposal-executions')) return []
    return []
  }, { role: 'member', blockAnalysisSessionStorage: true })
  await page.route('**/meeting-ai/api/**', async route => {
    analysisPosts++
    await route.fulfill({ json: {} })
  })

  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect(form).toContainText('无法保存本次请求编号')
  await expect(form).toContainText('需要浏览器会话存储')
  expect(analysisPosts).toBe(0)
})

test('401 during execution clears reviewer capability without showing success', async ({ page }) => {
  const requests = await setup(page, (path, request) => {
    if (request.method() === 'POST') return { status: 401, body: { detail: '登录已过期' } }
    if (path.endsWith('proposal-reviews')) {
      return { review_status: 'reviewed', proposals: [approvedReview()] }
    }
    if (path.endsWith('proposal-executions')) return []
    return [analysis()]
  })

  await page.getByRole('button', { name: '执行已批准变更' }).click()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('aiguanli_token'))).toBeNull()
  await expect(page.getByRole('region', { name: '故事状态提案' })).toHaveCount(0)
  await expect(page.getByText('执行结果已确认')).toHaveCount(0)
  expect(requests.filter(request => request.method === 'POST')).toHaveLength(1)
})
