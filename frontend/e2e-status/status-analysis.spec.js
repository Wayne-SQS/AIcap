import { test, expect } from '@playwright/test'

const proposal = (id = 'p1') => ({ proposal_id: id, story_id: 'US13', expected: { status: 1 }, changes: { status: 2 }, reason: '验收已完成', evidence: [{ segment_id: 'S1', quote: '<script>原文证据</script>' }] })
const analysis = (id = 'a1') => ({ id, client_request_id: id, submitted_by: 1, created_at: '2026-09-14 10:00:00', status: 'pending', transcript: '保存时原文', story_snapshots: [{ id: 'US13', title: '登录' }], result: { summary: `分析摘要 ${id}`, open_questions: ['谁负责回归？'], proposed_actions: [proposal()] } })
const review = (status = 'pending', execution_status = 'not_started') => ({ proposal_id: 'p1', original_proposal: proposal(), status, execution_status, approved_proposal: status === 'approved' ? { ...proposal(), changes: { status: 0 } } : null, decision: status === 'approved' ? 'modify_and_approve' : status === 'rejected' ? 'reject' : null, reason: '补充回归', reviewed_by: 2, reviewed_at: '2026-09-14 10:01:00' })

async function setup(page, resolve, role = 'viewer') {
  const requests = []
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'fixture-token'))
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.includes('status-analyses')) {
      requests.push({ method: request.method(), authorization: request.headers().authorization })
      const value = await resolve(path, request)
      return route.fulfill({ status: value?.error || 200, json: value?.error ? { detail: 'fixture failure' } : value })
    }
    let data = []
    if (path === '/api/health') data = { status: 'ok' }
    if (path === '/api/auth/me') data = { id: 1, role, display_name: '只读用户' }
    if (path === '/api/agent/config') data = { configured: false }
    if (path === '/api/meetings') data = [{ id: 'm1', title: '站会一', transcript: '目前会议原文' }, { id: 'm2', title: '站会二', transcript: '第二场' }]
    await route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  return requests
}

test('viewer reads original evidence, modified approval and durable execution without writes', async ({ page }) => {
  const requests = await setup(page, path => path.endsWith('proposal-reviews')
    ? { review_status: 'reviewed', proposals: [review('approved', 'succeeded')] }
    : path.endsWith('proposal-executions') ? [{ proposal_id: 'p1', previous_status: 1, new_status: 0, executed_by: 3, executed_at: '2026-09-14 10:02:00', story_log_id: 42 }]
      : [analysis()])
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('原始提案：进行中 → 已完成')
  await expect(panel).toContainText('批准后的变更：进行中 → 待办')
  await expect(panel).toContainText('执行成功')
  await expect(panel).toContainText('故事日志 #42')
  await expect(panel).toContainText('<script>原文证据</script>')
  await expect(panel.locator('script')).toHaveCount(0)
  await panel.getByText('分析保存时的会议原文').click()
  await expect(panel.locator('pre')).toHaveText('保存时原文')
  expect(requests.length).toBeGreaterThanOrEqual(3)
  expect(requests.every(r => r.method === 'GET' && r.authorization === 'Bearer fixture-token')).toBeTruthy()
})

test('switches batches and distinguishes pending, rejected and no changes', async ({ page }) => {
  await setup(page, path => {
    if (path.endsWith('proposal-executions')) return []
    if (path.endsWith('proposal-reviews')) return path.includes('/a2/')
      ? { review_status: 'no_changes', proposals: [] }
      : { review_status: 'partially_reviewed', proposals: [review(), { ...review('rejected', 'not_applicable'), proposal_id: 'p2', original_proposal: proposal('p2') }] }
    return [analysis(), { ...analysis('a2'), status: 'no_changes', result: { summary: '无需变更', proposed_actions: [], open_questions: [] } }]
  })
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('部分已审核')
  await expect(panel).toContainText('待审核 · 未执行')
  await expect(panel).toContainText('已拒绝 · 不适用')
  await page.getByLabel('状态分析记录').selectOption('a2')
  await expect(panel).toContainText('无状态变更提案')
  await expect(panel.locator('article')).toHaveCount(0)
})

test('list and detail failures are retryable and never rendered as pending or empty success', async ({ page }) => {
  let listFailed = true
  let detailFailed = true
  await setup(page, path => {
    if (path.endsWith('proposal-executions')) return []
    if (path.endsWith('proposal-reviews')) return detailFailed ? { error: 500 } : { review_status: 'pending', proposals: [review()] }
    return listFailed ? { error: 503 } : [analysis()]
  })
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('状态分析记录加载失败')
  listFailed = false
  await panel.getByRole('button', { name: '刷新状态提案' }).click()
  await expect(panel).toContainText('审核和执行状态加载失败')
  await expect(panel.locator('article')).toHaveCount(0)
  detailFailed = false
  await panel.getByRole('button', { name: '重试加载处理状态' }).click()
  await expect(panel).toContainText('待审核 · 未执行')
})

test('late results from previous meeting cannot replace current empty state', async ({ page }) => {
  let release
  let started
  const arrived = new Promise(resolve => { started = resolve })
  const gate = new Promise(resolve => { release = resolve })
  await setup(page, async path => {
    if (path.includes('/m2/')) return []
    if (path.endsWith('proposal-reviews')) {
      started()
      await gate
      return { review_status: 'reviewed', proposals: [review('approved')] }
    }
    if (path.endsWith('proposal-executions')) return []
    return [analysis()]
  })
  await arrived
  await page.locator('#meeting-select').selectOption('m2')
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('此会议暂无已保存的状态分析')
  const response = page.waitForResponse(r => r.url().includes('/a1/proposal-reviews'))
  release()
  await response
  await expect(panel.locator('article')).toHaveCount(0)
  await expect(panel).not.toContainText('分析摘要 a1')
  await page.locator('#meeting-select').selectOption('')
  await expect(panel).toContainText('请先选择已保存会议')
})

for (const role of ['viewer', 'member']) {
  test(`${role} has no controls for pending status proposals`, async ({ page }) => {
    const requests = await setup(page, path => path.endsWith('proposal-reviews')
      ? { review_status: 'pending', proposals: [review()] }
      : path.endsWith('proposal-executions') ? [] : [analysis()], role)
    await expect(page.getByRole('region', { name: '故事状态提案' })).toContainText('待审核 · 未执行')
    await expect(page.getByRole('form', { name: '审核状态提案' })).toHaveCount(0)
    expect(requests.every(request => request.method === 'GET')).toBeTruthy()
  })
}

for (const decision of ['approve', 'modify_and_approve', 'reject']) {
  test(`reviewer submits ${decision} and reloads durable decision without execution`, async ({ page }) => {
    let saved = null
    const posted = []
    const requests = await setup(page, (path, request) => {
      if (request.method() === 'POST') {
        const body = request.postDataJSON()
        posted.push(body)
        saved = { ...review(decision === 'reject' ? 'rejected' : 'approved', decision === 'reject' ? 'not_applicable' : 'not_started'), decision, reason: body.reason,
          approved_proposal: decision === 'reject' ? null : { ...proposal(), changes: body.changes || proposal().changes } }
        return saved
      }
      if (path.endsWith('proposal-reviews')) return { review_status: saved ? 'reviewed' : 'pending', proposals: [saved || review()] }
      return path.endsWith('proposal-executions') ? [] : [analysis()]
    }, decision === 'modify_and_approve' ? 'owner' : 'admin')
    const form = page.getByRole('form', { name: '审核状态提案' })
    await form.getByLabel('审核决定').selectOption(decision)
    if (decision === 'modify_and_approve') {
      await expect(form.getByLabel('批准后的目标状态').locator('option')).toHaveText(['请选择目标状态', '待办'])
      await form.getByLabel('批准后的目标状态').selectOption('0')
      await form.getByLabel('修改原因（必填）').fill('   ')
      await form.getByRole('button', { name: '提交审核决定' }).click()
      await expect(form).toContainText('请选择新的目标状态并填写修改原因')
      expect(posted).toHaveLength(0)
      await form.getByLabel('修改原因（必填）').fill('补充回归')
    }
    await form.getByRole('button', { name: '提交审核决定' }).click()
    const panel = page.getByRole('region', { name: '故事状态提案' })
    await expect(panel).toContainText('审核决定已保存')
    await expect(panel).toContainText('全部已审核')
    await expect(form).toHaveCount(0)
    expect(posted).toEqual([{ proposal_id: 'p1', decision, reason: decision === 'modify_and_approve' ? '补充回归' : '', ...(decision === 'modify_and_approve' ? { changes: { status: 0 } } : {}) }])
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(1)
    expect(requests.every(request => request.authorization === 'Bearer fixture-token')).toBeTruthy()
    await expect(panel.locator('article')).not.toContainText('执行成功')
  })
}

test('failed review preserves input and supports exact retry with duplicate clicks blocked', async ({ page }) => {
  let saved = null
  let release
  const gate = new Promise(resolve => { release = resolve })
  const bodies = []
  await setup(page, async (path, request) => {
    if (request.method() === 'POST') {
      bodies.push(request.postDataJSON())
      if (bodies.length === 1) { await gate; return { error: 503 } }
      saved = { ...review('approved'), decision: 'approve', approved_proposal: proposal(), reason: '已核对' }
      return saved
    }
    if (path.endsWith('proposal-reviews')) return { review_status: saved ? 'reviewed' : 'pending', proposals: [saved || review()] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'admin')
  const form = page.getByRole('form', { name: '审核状态提案' })
  await form.getByLabel('审核意见（可选）').fill('已核对')
  await form.getByRole('button', { name: '提交审核决定' }).click()
  await expect(form.getByRole('button', { name: '正在提交审核' })).toBeDisabled()
  await expect(form.getByLabel('审核决定')).toBeDisabled()
  release()
  await expect(form).toContainText('未能确认审核结果')
  await expect(form.getByLabel('审核意见（可选）')).toHaveValue('已核对')
  await form.getByRole('button', { name: '提交审核决定' }).click()
  await expect(form).toHaveCount(0)
  expect(bodies).toHaveLength(2)
  expect(bodies[0]).toEqual(bodies[1])
})

test('conflict refresh loads another reviewer decision instead of overwriting it', async ({ page }) => {
  let conflicted = false
  await setup(page, (path, request) => {
    if (request.method() === 'POST') { conflicted = true; return { error: 409 } }
    if (path.endsWith('proposal-reviews')) return { review_status: conflicted ? 'reviewed' : 'pending', proposals: [conflicted ? review('rejected', 'not_applicable') : review()] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'owner')
  const form = page.getByRole('form', { name: '审核状态提案' })
  await form.getByRole('button', { name: '提交审核决定' }).click()
  await expect(form).toContainText('未能确认审核结果')
  await form.getByRole('button', { name: '刷新处理状态' }).click()
  await expect(form).toHaveCount(0)
  await expect(page.getByRole('region', { name: '故事状态提案' })).toContainText('已拒绝 · 不适用')
})

test('late review completion after meeting switch does not refresh or notify the new meeting', async ({ page }) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  await setup(page, async (path, request) => {
    if (request.method() === 'POST') { await gate; return review('approved') }
    if (path.includes('/m2/')) return []
    if (path.endsWith('proposal-reviews')) return { review_status: 'pending', proposals: [review()] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'admin')
  const form = page.getByRole('form', { name: '审核状态提案' })
  await form.getByRole('button', { name: '提交审核决定' }).click()
  await page.locator('#meeting-select').selectOption('m2')
  const response = page.waitForResponse(r => r.request().method() === 'POST')
  release()
  await response
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('此会议暂无已保存的状态分析')
  await expect(panel).not.toContainText('审核决定已保存')
})

const executionResult = () => ({ analysis_id: 'a1', proposal_id: 'p1', execution_status: 'succeeded', story_id: 'US13', previous_status: 1, new_status: 0, story_log_id: 42, executed_by: 2, executed_at: '2026-09-14 11:00:00' })

for (const role of ['viewer', 'member']) {
  test(`${role} cannot execute approved proposals`, async ({ page }) => {
    const requests = await setup(page, path => path.endsWith('proposal-reviews') ? { review_status: 'reviewed', proposals: [review('approved')] }
      : path.endsWith('proposal-executions') ? [] : [analysis()], role)
    await expect(page.getByRole('region', { name: '故事状态提案' })).toContainText('已批准 · 未执行')
    await expect(page.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
    expect(requests.every(r => r.method === 'GET')).toBeTruthy()
  })
}

for (const role of ['admin', 'owner']) {
  test(`${role} executes approved target with only proposal id and sees durable audit`, async ({ page }) => {
    let executed = false
    const posted = []
    const requests = await setup(page, (path, request) => {
      if (request.method() === 'POST') {
        expect(path).toBe('/api/meetings/m1/status-analyses/a1/proposal-executions')
        posted.push(request.postDataJSON()); executed = true; return executionResult()
      }
      if (path.endsWith('proposal-reviews')) return { review_status: 'reviewed', proposals: [review('approved', executed ? 'succeeded' : 'not_started')] }
      return path.endsWith('proposal-executions') ? (executed ? [executionResult()] : []) : [analysis()]
    }, role)
    await page.getByRole('button', { name: '执行已批准变更' }).click()
    const panel = page.getByRole('region', { name: '故事状态提案' })
    await expect(panel).toContainText('执行结果已确认，故事日志 #42')
    await expect(panel.locator('article')).toContainText('执行结果：进行中 → 待办')
    await expect(panel.locator('article')).toContainText('已批准 · 执行成功')
    await expect(page.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
    expect(posted).toEqual([{ proposal_id: 'p1' }])
    expect(requests.every(r => r.authorization === 'Bearer fixture-token')).toBeTruthy()
  })
}

test('execution response failure permits idempotent retry and disables repeated clicks', async ({ page }) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const bodies = []
  await setup(page, async (path, request) => {
    if (request.method() === 'POST') {
      bodies.push(request.postDataJSON())
      if (bodies.length === 1) { await gate; return { error: 503 } }
      return executionResult()
    }
    if (path.endsWith('proposal-reviews')) return { review_status: 'reviewed', proposals: [review('approved')] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'admin')
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(page.getByRole('button', { name: '正在执行' })).toBeDisabled()
  release()
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('未能确认执行结果')
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  await expect(panel.locator('article')).toContainText('已批准 · 执行成功')
  await expect(page.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
  expect(bodies).toEqual([{ proposal_id: 'p1' }, { proposal_id: 'p1' }])
})

test('execution conflict remains unexecuted and refresh can discover another successful executor', async ({ page }) => {
  let latest = false
  await setup(page, (path, request) => {
    if (request.method() === 'POST') return { error: 409 }
    if (path.endsWith('proposal-reviews')) return { review_status: 'reviewed', proposals: [review('approved', latest ? 'succeeded' : 'not_started')] }
    return path.endsWith('proposal-executions') ? (latest ? [executionResult()] : []) : [analysis()]
  }, 'owner')
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('未能确认执行结果')
  await expect(panel).not.toContainText('执行结果已确认')
  latest = true
  await page.getByRole('button', { name: '刷新执行状态' }).click()
  await expect(panel.locator('article')).toContainText('故事日志 #42')
  await expect(page.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
})

test('confirmed execution survives subsequent detail refresh failure', async ({ page }) => {
  let executed = false
  let failRead = true
  await setup(page, (path, request) => {
    if (request.method() === 'POST') { executed = true; return executionResult() }
    if (path.endsWith('proposal-reviews')) return executed && failRead ? { error: 500 } : { review_status: 'reviewed', proposals: [review('approved')] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'admin')
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('执行结果已确认，故事日志 #42')
  await expect(panel).toContainText('审核和执行状态加载失败')
  failRead = false
  await page.getByRole('button', { name: '重试加载处理状态' }).click()
  await expect(panel.locator('article')).toContainText('已批准 · 执行成功')
  await expect(page.getByRole('button', { name: '执行已批准变更' })).toHaveCount(0)
})

test('late execution completion cannot contaminate another meeting', async ({ page }) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  await setup(page, async (path, request) => {
    if (request.method() === 'POST') { await gate; return executionResult() }
    if (path.includes('/m2/')) return []
    if (path.endsWith('proposal-reviews')) return { review_status: 'reviewed', proposals: [review('approved')] }
    return path.endsWith('proposal-executions') ? [] : [analysis()]
  }, 'admin')
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  await page.locator('#meeting-select').selectOption('m2')
  const response = page.waitForResponse(r => r.request().method() === 'POST')
  release(); await response
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('此会议暂无已保存的状态分析')
  await expect(panel).not.toContainText('执行结果已确认')
})

const savedAnalysis = (request, storage_status = 'pending') => ({ analysis_id: 'new-analysis', client_request_id: request.client_request_id, meeting_id: 'm1', storage_status })
const readAnalysisFixture = path => path.endsWith('proposal-reviews') ? { review_status: 'pending', proposals: [review()] } : path.endsWith('proposal-executions') ? [] : [analysis()]

test('analysis saves then selects returned record and starts a new intention only after success', async ({ page }) => {
  let saved = false
  await setup(page, path => path.endsWith('proposal-reviews') ? { review_status: 'no_changes', proposals: [] }
    : path.endsWith('proposal-executions') ? [] : saved ? [analysis(), analysis('new-analysis')] : [analysis()], 'member')
  const bodies = []
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON(); bodies.push(body); saved = true
    expect(route.request().headers().authorization).toBe('Bearer fixture-token')
    expect(route.request().method()).toBe('POST')
    await route.fulfill({ json: savedAnalysis(body, 'no_changes') })
  })
  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await form.getByLabel('本次 Sprint（可选）').selectOption('2')
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect(form.getByRole('status')).toContainText('分析已保存，本次没有状态变更提案')
  await expect(page.getByLabel('状态分析记录')).toHaveValue('new-analysis')
  expect(bodies[0]).toEqual({ client_request_id: expect.stringMatching(/^[a-z0-9-]{1,80}$/), meeting_type: 'daily_scrum', current_sprint: 2 })
  await form.getByRole('button', { name: '重新分析每日站会' }).click()
  await expect.poll(() => bodies.length).toBe(2)
  expect(bodies[1].client_request_id).not.toBe(bodies[0].client_request_id)
})

test('uncertain analysis preserves request across reload and blocks duplicate submission', async ({ page }) => {
  await setup(page, readAnalysisFixture, 'admin')
  let release
  const gate = new Promise(resolve => { release = resolve })
  const bodies = []
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON(); bodies.push(body)
    if (bodies.length === 1) { await gate; return route.fulfill({ status: 504, json: { detail: 'storage_outcome_unknown', client_request_id: body.client_request_id } }) }
    await route.fulfill({ json: savedAnalysis(body) })
  })
  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect(form.getByRole('button', { name: '正在分析并保存' })).toBeDisabled()
  await expect(form.getByLabel('本次 Sprint（可选）')).toBeDisabled()
  release()
  await expect(form).toContainText('未能确认分析保存结果')
  await page.reload()
  await form.getByRole('button', { name: '重试本次分析' }).click()
  await expect(form.getByRole('status')).toContainText('分析已保存')
  expect(bodies).toHaveLength(2)
  expect(bodies[1]).toEqual(bodies[0])
  expect(bodies[0].current_sprint).toBeNull()
})

test('invalid save response is retryable and changing sprint creates a separate request', async ({ page }) => {
  await setup(page, readAnalysisFixture, 'owner')
  const bodies = []
  await page.route('**/meeting-ai/api/**', async route => {
    bodies.push(route.request().postDataJSON())
    await route.fulfill({ json: { analysis_id: 'bad' } })
  })
  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect(form).toContainText('保存确认无效')
  await form.getByRole('button', { name: '重试本次分析' }).click()
  await expect.poll(() => bodies.length).toBe(2)
  await expect(form).toContainText('保存确认无效')
  await form.getByLabel('本次 Sprint（可选）').selectOption('3')
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect.poll(() => bodies.length).toBe(3)
  expect(bodies[0]).toEqual(bodies[1])
  expect(bodies[2].client_request_id).not.toBe(bodies[0].client_request_id)
  expect(bodies[2].current_sprint).toBe(3)
})

test('viewer cannot start analysis and unselected meeting disables initiation', async ({ page }) => {
  await setup(page, readAnalysisFixture)
  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await expect(form).toContainText('负责人、管理员或成员可发起分析')
  await expect(form.getByRole('button')).toHaveCount(0)
})

test('late analysis save does not update another meeting and missing selection disables action', async ({ page }) => {
  await setup(page, path => path.includes('/m2/') ? [] : readAnalysisFixture(path), 'member')
  let release
  const gate = new Promise(resolve => { release = resolve })
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON(); await gate
    await route.fulfill({ json: savedAnalysis(body) })
  })
  const form = page.getByRole('region', { name: '每日站会状态分析' })
  await form.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await page.locator('#meeting-select').selectOption('m2')
  const response = page.waitForResponse(r => r.url().includes('/meeting-ai/'))
  release(); await response
  await expect(form.getByRole('status')).toHaveCount(0)
  await expect(page.getByRole('region', { name: '故事状态提案' })).toContainText('此会议暂无已保存的状态分析')
  await page.locator('#meeting-select').selectOption('')
  await expect(form.getByRole('button', { name: '分析每日站会', exact: true })).toBeDisabled()
})

test('saved analysis remains confirmed when proposal listing fails', async ({ page }) => {
  let saved = false
  await setup(page, path => saved ? { error: 503 } : readAnalysisFixture(path), 'admin')
  await page.route('**/meeting-ai/api/**', async route => {
    saved = true; await route.fulfill({ json: savedAnalysis(route.request().postDataJSON()) })
  })
  await page.getByRole('button', { name: '分析每日站会', exact: true }).click()
  await expect(page.getByRole('region', { name: '每日站会状态分析' }).getByRole('status')).toContainText('分析已保存')
  await expect(page.getByRole('region', { name: '故事状态提案' })).toContainText('状态分析记录加载失败')
})

test('vite proxy forwards exact analyze path and bearer token to local AI HTTP fixture', async ({ page }) => {
  const { createServer } = await import('node:http')
  let received
  const server = createServer(async (req, res) => {
    let text = ''; for await (const chunk of req) text += chunk
    const body = JSON.parse(text)
    received = { path: req.url, authorization: req.headers.authorization, body }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(savedAnalysis(body)))
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(18090, '127.0.0.1', resolve) })
  try {
    await setup(page, readAnalysisFixture, 'member')
    await page.getByRole('button', { name: '分析每日站会', exact: true }).click()
    await expect(page.getByRole('region', { name: '每日站会状态分析' }).getByRole('status')).toContainText('分析已保存')
    expect(received.path).toBe('/api/meetings/m1/analyze')
    expect(received.authorization).toBe('Bearer fixture-token')
    expect(received.body.meeting_type).toBe('daily_scrum')
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
})

test('confirmed execution survives board refresh failure and allows read-only retry', async ({ page }) => {
  let executed = false
  let failRead = true
  let posts = 0
  await setup(page, (path, request) => {
    if (request.method() === 'POST') { executed = true; posts++; return executionResult() }
    if (path.endsWith('proposal-reviews')) return { review_status: 'reviewed', proposals: [review('approved', executed ? 'succeeded' : 'not_started')] }
    return path.endsWith('proposal-executions') ? (executed ? [executionResult()] : []) : [analysis()]
  }, 'admin')
  await page.route('http://127.0.0.1:8080/api/stories', async route => {
    if (executed && failRead) return route.fulfill({ status: 503, json: { detail: 'unavailable' } })
    await route.fallback()
  })
  await page.getByRole('button', { name: '执行已批准变更' }).click()
  const panel = page.getByRole('region', { name: '故事状态提案' })
  await expect(panel).toContainText('执行结果已确认，但看板和日志刷新失败')
  await expect(panel.locator('article')).toContainText('已批准 · 执行成功')
  failRead = false
  await page.getByRole('button', { name: '刷新看板和日志' }).click()
  await expect(panel).not.toContainText('看板和日志刷新失败')
  expect(posts).toBe(1)
})
