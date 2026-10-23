import { test, expect } from '@playwright/test'
const panel = page => page.getByRole('region', { name: 'Assignment分配候选', exact: true })
const history = page => page.getByRole('region', { name: '分配建议历史与审核', exact: true })
function record(body) {
  const { client_request_id, ...input } = body
  return { id: 'a1', meeting_id: 'm1', client_request_id, submitted_by: 1, created_at: '2026-10-01', input, review: null,
    result: { rule_version: 'assignment-skills-v1', status: 'provisional', requirements_source: 'caller_supplied', writes_performed: false, excluded_viewer_ids: [],
      context: { meeting_id: 'm1', target_sprint: input.target_sprint, scope: 'read_only_preparation', snapshot_consistency: 'sequential_reads', selected_stories: [{ id: 'US13', title: '登录', status: 0, sprint: 2, owner_id: null }], tasks: [], gaps: [], members: [{ profile: { user_id: 7, role: 'member', display_name: '成员甲', six_week_capacity_hours: 60 }, owned_story_ids: [], active_task_ids: [], target_sprint_task_ids: null }] },
      candidates: [{ member_id: 7, display_name: '成员甲', rank: 1, matched_requirements: 1, total_requirements: 1, capacity_check: 'unknown', suitability: 'requires_human_review', matches: [{ requirement: input.requirements[0], recorded_level: 3, meets_requirement: true }] }] } }
}
const input = { story_ids: ['US13'], target_sprint: null, requirements: [{ dimension: 'tech_stack', name: 'Python', minimum_level: 3 }], client_request_id: 'r1' }
async function setup(page, role = 'admin', preloaded = false, sharedState = null) {
  const state = sharedState || { saved: preloaded ? record(input) : null, intent: null, saves: [], reviews: [], executes: [], loseSave: false, failBeforeSave: false, failClear: false, loseExecution: false, invalidExecution: false, failNextList: false }
  await page.addInitScript(() => { localStorage.setItem('aiguanli_token', 'fixture-token'); localStorage.setItem('aiguanli-current-project', 'P001') })
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON(); state.saves.push(body)
    if (state.failBeforeSave) { await route.fulfill({ status: 503, json: { detail: 'storage unavailable' } }); return }
    state.saved ||= record(body)
    await route.fulfill(state.loseSave ? { status: 503, json: { detail: 'storage_outcome_unknown' } } : { json: state.saved })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    let data = []
    if (path.endsWith('/health')) data = { status: 'ok' }
    if (path.endsWith('/auth/me')) data = { id: 1, role, display_name: role }
    if (path.endsWith('/projects')) data = [{ id: 'P001', name: '测试项目', can_write: role !== 'viewer', can_manage: role === 'admin' }]
    if (path.endsWith('/agent/config')) data = { configured: false }
    if (path.endsWith('/meetings')) data = [{ id: 'm1', title: '分配会议' }, { id: 'm2', title: '下一会议' }]
    if (path.endsWith('/stories')) data = [{ id: 'US13', title: '登录' }]
    if (path.endsWith('/assignment-suggestions/save-intent')) {
      if (req.method() === 'PUT') {
        const intent = req.postDataJSON()
        if (state.intent && JSON.stringify(state.intent) !== JSON.stringify(intent)) {
          await route.fulfill({ status: 409, json: { detail: '已有未确认的分配保存请求' } }); return
        }
        state.intent = intent
      }
      if (req.method() === 'DELETE') {
        if (state.failClear) { await route.fulfill({ status: 503, json: { detail: 'cleanup unavailable' } }); return }
        if (new URL(req.url()).searchParams.get('client_request_id') !== state.intent?.client_request_id) {
          await route.fulfill({ status: 409, json: { detail: '待恢复请求已变化' } }); return
        }
        state.intent = null
        data = { cleared: true }
      } else data = { intent: state.intent }
    }
    if (path.endsWith('/assignment-suggestions')) {
      if (state.failNextList) { state.failNextList = false; await route.fulfill({ status: 503, json: {} }); return }
      data = path.includes('/m1/') && state.saved ? [state.saved] : []
    }
    if (path.endsWith('/a1/review')) {
      const body = req.postDataJSON(); state.reviews.push(body)
      data = { input: body, reviewed_by: 1, reviewed_at: '2026-10-01', status: body.decision === 'approve' ? 'approved' : 'rejected', execution_status: body.decision === 'approve' ? 'not_started' : 'not_applicable' }
      state.saved.review = data
    }
    if (path.endsWith('/a1/execute')) {
      state.executes.push(req.postDataJSON())
      data = { suggestion_id: 'a1', meeting_id: 'm1', story_id: 'US13', previous_owner_id: null, new_owner_id: 7, story_log_id: 41, executed_by: 1, executed_at: '2026-10-01', execution_status: 'succeeded' }
      if (state.invalidExecution) data = { execution_status: 'succeeded' }
      else state.saved.review = { ...state.saved.review, execution_status: 'succeeded', execution: data }
      if (state.loseExecution) { await route.fulfill({ status: 503, json: { detail: 'response lost' } }); return }
    }
    await route.fulfill({ json: data })
  })
  await page.goto('/#/projects/P001/office?tab=meeting&type=assignment&meetingId=m1')
  await expect(panel(page)).toBeVisible()
  return state
}
async function save(page) {
  await panel(page).getByLabel('待分配故事').selectOption('US13')
  await panel(page).getByLabel('技能名称', { exact: true }).fill('Python')
  await panel(page).getByRole('button', { name: '保存分配建议', exact: true }).click()
}
async function approve(page) {
  await history(page).getByLabel('分配给成员').selectOption('7')
  await history(page).getByLabel('分配审核理由').fill('已人工核对分工')
  await history(page).getByRole('checkbox').check()
  await history(page).getByRole('button', { name: '提交分配审核' }).click()
}
test('Assignment save approve execute and reload retains durable audit', async ({ page }) => {
  const state = await setup(page)
  await save(page); await approve(page)
  await expect(history(page)).toContainText('批准负责人：#7')
  await history(page).getByRole('button', { name: '执行已批准分配' }).click()
  await expect(history(page)).toContainText('故事日志 #41')
  await page.reload()
  await expect(history(page)).toContainText('故事日志 #41')
  await expect(history(page).getByRole('button', { name: '执行已批准分配' })).toHaveCount(0)
  expect(state.saves).toHaveLength(1); expect(state.reviews).toHaveLength(1); expect(state.executes).toEqual([{}])
})
test('Assignment uncertain save survives reload with exact original request', async ({ page }) => {
  const state = await setup(page); state.loseSave = true
  await save(page)
  await expect(panel(page)).toContainText('未能完成建议保存确认')
  await page.reload()
  state.loseSave = false
  await panel(page).getByRole('button', { name: '重试保存分配建议' }).click()
  await expect(panel(page).getByText('分配建议已保存，请在下方核对保存的快照并审核。', { exact: true })).toBeVisible()
  expect(state.saves).toHaveLength(2); expect(state.saves[1]).toEqual(state.saves[0])
})
for (const role of ['member', 'viewer']) test(`Assignment ${role} reads history without review or execution`, async ({ page }) => {
  await setup(page, role, true)
  await expect(history(page)).toContainText('成员甲')
  await expect(history(page).getByRole('button', { name: '提交分配审核' })).toHaveCount(0)
  await expect(history(page).getByRole('button', { name: '执行已批准分配' })).toHaveCount(0)
  if (role === 'viewer') await expect(panel(page).getByRole('button', { name: '保存分配建议', exact: true })).toHaveCount(0)
})
test('Assignment rejection needs no member and cannot execute', async ({ page }) => {
  const state = await setup(page, 'admin', true)
  await history(page).getByLabel('分配审核决定').selectOption('reject')
  await history(page).getByLabel('分配审核理由').fill('暂不安排')
  await history(page).getByRole('button', { name: '提交分配审核' }).click()
  await expect(history(page)).toContainText('已拒绝')
  await expect(history(page).getByRole('button', { name: '执行已批准分配' })).toHaveCount(0)
  expect(state.reviews[0].member_id).toBeNull()
})
test('Assignment malformed execution cannot display success', async ({ page }) => {
  const state = await setup(page, 'admin', true); state.invalidExecution = true
  await approve(page); await history(page).getByRole('button', { name: '执行已批准分配' }).click()
  await expect(history(page)).toContainText('未能确认执行结果')
  await expect(history(page)).not.toContainText('分配已执行')
})
test('Assignment list failure does not claim empty history and permits retry', async ({ page }) => {
  const state = await setup(page, 'admin', true)
  await expect(history(page)).toContainText('成员甲')
  state.failNextList = true
  await history(page).getByRole('button', { name: '刷新分配记录' }).click()
  await expect(history(page)).toContainText('读取建议失败')
  await expect(history(page)).not.toContainText('暂无已保存建议')
  await history(page).getByRole('button', { name: '刷新分配记录' }).click()
  await expect(history(page).getByRole('alert')).toHaveCount(0)
})
test('Assignment execution response after meeting switch cannot contaminate new meeting', async ({ page }) => {
  await setup(page, 'admin', true); await approve(page)
  let release
  const gate = new Promise(resolve => { release = resolve })
  await page.route('**/a1/execute', async route => { await gate; await route.fulfill({ status: 409, json: { detail: '故事已变化' } }) })
  const request = page.waitForRequest('**/a1/execute')
  await history(page).getByRole('button', { name: '执行已批准分配' }).click(); await request
  await page.locator('#meeting-select').selectOption('m2')
  const finished = page.waitForResponse('**/a1/execute'); release(); await finished
  await expect(history(page)).toContainText('暂无已保存建议')
  await expect(history(page)).not.toContainText('未能确认执行结果')
})
test('Assignment committed execution with lost response is recovered from history', async ({ page }) => {
  const state = await setup(page, 'admin', true); await approve(page); state.loseExecution = true
  await history(page).getByRole('button', { name: '执行已批准分配' }).click()
  await expect(history(page)).toContainText('未能确认执行结果')
  await history(page).getByRole('button', { name: '刷新分配记录' }).click()
  await expect(history(page)).toContainText('故事日志 #41')
  await expect(history(page).getByRole('button', { name: '执行已批准分配' })).toHaveCount(0)
  expect(state.executes).toHaveLength(1)
})
test('Assignment unavailable intent storage blocks suggestion request', async ({ page }) => {
  const state = await setup(page)
  await page.route('**/assignment-suggestions/save-intent', route => route.fulfill({ status: 503, json: { detail: 'intent unavailable' } }), { times: 1 })
  await save(page)
  await expect(panel(page)).toContainText('保存未发出')
  expect(state.saves).toEqual([])
})

test('Assignment save intent resumes in a different browser with the original request', async ({ page, browser }) => {
  const state = await setup(page)
  state.loseSave = true
  await save(page)
  await expect(panel(page)).toContainText('未能完成建议保存确认')
  const otherContext = await browser.newContext()
  try {
    const otherPage = await otherContext.newPage()
    state.loseSave = false
    await setup(otherPage, 'admin', false, state)
    await expect(panel(otherPage)).toContainText(`有尚未确认的保存请求（${state.saves[0].client_request_id}）`)
    await panel(otherPage).getByRole('button', { name: '重试保存分配建议' }).click()
    await expect(panel(otherPage).getByText('分配建议已保存，请在下方核对保存的快照并审核。', { exact: true })).toBeVisible()
    expect(state.saves).toHaveLength(2)
    expect(state.saves[1]).toEqual(state.saves[0])
    expect(state.intent).toBeNull()
  } finally { await otherContext.close() }
})

test('Assignment abandoned pending request no longer blocks a new save', async ({ page }) => {
  const state = await setup(page)
  state.failBeforeSave = true
  await save(page)
  await expect(panel(page)).toContainText('未能完成建议保存确认')
  page.once('dialog', dialog => dialog.accept())
  await panel(page).getByRole('button', { name: '放弃待恢复请求' }).click()
  await expect(panel(page)).toContainText('已放弃待恢复请求')
  expect(state.intent).toBeNull()
  await expect(panel(page).getByRole('button', { name: '保存分配建议', exact: true })).toBeEnabled()
  state.failBeforeSave = false
  await panel(page).getByRole('button', { name: '保存分配建议', exact: true }).click()
  await expect(panel(page).getByText('分配建议已保存，请在下方核对保存的快照并审核。', { exact: true })).toBeVisible()
  expect(state.saves[1].client_request_id).not.toBe(state.saves[0].client_request_id)
})

test('Assignment save stays visibly confirmed when intent cleanup fails', async ({ page }) => {
  const state = await setup(page)
  state.failClear = true
  await save(page)
  await expect(panel(page).getByText('分配建议已保存，请在下方核对保存的快照并审核。', { exact: true })).toBeVisible()
  await expect(panel(page)).toContainText('待恢复请求清理失败')
  await expect(history(page)).toContainText('成员甲')
  state.failClear = false
  await panel(page).getByRole('button', { name: '重试保存分配建议' }).click()
  await expect(panel(page).getByRole('button', { name: '重试保存分配建议' })).toHaveCount(0)
  expect(state.saves).toHaveLength(2)
  expect(state.saves[1]).toEqual(state.saves[0])
  expect(state.intent).toBeNull()
})
