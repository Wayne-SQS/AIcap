import { test, expect } from '@playwright/test'

const panelOf = page => page.getByRole('region', { name: 'Assignment分配候选', exact: true })
function response(body, meetingId = 'm1') {
  return {
    rule_version: 'assignment-skills-v1', writes_performed: false, requirements_source: 'caller_supplied', status: 'provisional', excluded_viewer_ids: [9],
    context: { meeting_id: meetingId, target_sprint: body.target_sprint, scope: 'read_only_preparation', snapshot_consistency: 'sequential_reads',
      selected_stories: [{ id: body.story_ids[0], title: '发布检查' }], tasks: [], gaps: [{ code: 'sprint_capacity_unavailable' }],
      members: [7, 8].map(id => ({ profile: { user_id: id, role: 'member', six_week_capacity_hours: 60 }, owned_story_ids: ['US13'], active_task_ids: ['T01'], target_sprint_task_ids: body.target_sprint === null ? null : ['T01'] })) },
    candidates: [7, 8].map((id, index) => ({ member_id: id, display_name: `成员${id}`, rank: 1, matched_requirements: body.requirements.length, total_requirements: body.requirements.length, capacity_check: 'unknown', suitability: 'requires_human_review',
      matches: body.requirements.map(requirement => ({ requirement, recorded_level: index ? 5 : 3, meets_requirement: true })) }))
  }
}
async function setup(page, role = 'admin', open = true) {
  const posts = [], writes = []
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'fixture-token'))
  await page.route('**/meeting-ai/api/**', async route => {
    const body = route.request().postDataJSON()
    posts.push(body)
    expect(route.request().headers().authorization).toBe('Bearer fixture-token')
    await route.fulfill({ json: response(body) })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (req.method() !== 'GET') writes.push(path)
    let data = []
    if (path.endsWith('/health')) data = { status: 'ok' }
    if (path.endsWith('/auth/me')) data = { id: 1, role, display_name: role }
    if (path.endsWith('/agent/config')) data = { configured: false }
    if (path.endsWith('/meetings')) data = [{ id: 'm1', title: '会议一' }, { id: 'm2', title: '会议二' }]
    if (path.endsWith('/stories')) data = [{ id: 'US13', title: '发布检查' }, { id: 'US14', title: '接口测试' }]
    await route.fulfill({ json: data })
  })
  await page.goto('/#/ai')
  if (open) await page.getByRole('button', { name: '打开分配候选' }).click()
  return { posts, writes }
}
async function fill(page) {
  const panel = panelOf(page)
  await panel.getByLabel('待分配故事').selectOption('US13')
  await panel.getByLabel('技能名称').fill('Python')
}
async function query(page) { await panelOf(page).getByRole('button', { name: '查询分配候选', exact: true }).click() }

test('Assignment explicit requirements show tied ranks, evidence and unknown capacity without writes', async ({ page }) => {
  const { posts, writes } = await setup(page), panel = panelOf(page)
  await fill(page); await query(page)
  await expect(panel.getByRole('heading', { name: /第 1 名/ })).toHaveCount(2)
  await expect(panel).toContainText('画像 3；满足')
  await expect(panel).toContainText('画像 5；满足')
  await expect(panel).toContainText('与目标Sprint重叠的任务：未知')
  await expect(panel).toContainText('容量尚未验证，不能据此直接分配')
  await expect(panel).toContainText('只读角色不进入候选：#9')
  expect(posts).toEqual([{ story_ids: ['US13'], target_sprint: null, requirements: [{ dimension: 'tech_stack', name: 'Python', minimum_level: 3 }] }])
  expect(writes).toEqual([])
  await panel.getByLabel('目标Sprint').selectOption('2')
  await expect(panel.getByRole('region', { name: '候选结果' })).toHaveCount(0)
  await query(page)
  await expect(panel).toContainText('与目标Sprint重叠的任务：T01')
  await panel.getByLabel('待分配故事').selectOption('US14')
  await expect(panel.getByRole('region', { name: '候选结果' })).toHaveCount(0)
})
test('Assignment duplicate requirements are blocked locally', async ({ page }) => {
  const { posts } = await setup(page), panel = panelOf(page)
  await fill(page)
  await panel.getByRole('button', { name: '添加技能要求' }).click()
  await panel.getByLabel('技能名称').nth(1).fill(' python ')
  await query(page)
  await expect(panel.getByRole('alert')).toContainText('不能重复')
  expect(posts).toEqual([])
})
test('Assignment request failure preserves input and permits retry', async ({ page }) => {
  await setup(page); await fill(page)
  await page.route('**/assignment/recommendations', route => route.fulfill({ status: 503, json: { detail: '服务暂不可用' } }), { times: 1 })
  await query(page)
  await expect(panelOf(page).getByRole('alert')).toContainText('候选查询失败')
  await expect(panelOf(page).getByLabel('技能名称')).toHaveValue('Python')
  await query(page)
  await expect(panelOf(page).getByRole('region', { name: '候选结果' })).toBeVisible()
})
for (const invalid of ['meeting', 'evidence']) test(`Assignment rejects invalid ${invalid} response`, async ({ page }) => {
  await setup(page); await fill(page)
  await page.route('**/assignment/recommendations', route => {
    const data = response(route.request().postDataJSON())
    if (invalid === 'meeting') data.context.meeting_id = 'other'
    else data.candidates[0].matches[0].recorded_level = 1
    return route.fulfill({ json: data })
  })
  await query(page)
  await expect(panelOf(page).getByRole('alert')).toContainText('候选查询失败')
  await expect(panelOf(page).getByRole('region', { name: '候选结果' })).toHaveCount(0)
})
test('Assignment viewer cannot submit', async ({ page }) => {
  const { posts } = await setup(page, 'viewer')
  await expect(panelOf(page)).toContainText('只读角色不能发起查询')
  await expect(panelOf(page).getByRole('button', { name: '查询分配候选' })).toHaveCount(0)
  expect(posts).toEqual([])
})
test('Assignment late result cannot cross meeting selection', async ({ page }) => {
  await setup(page); await fill(page)
  let release
  const gate = new Promise(resolve => { release = resolve })
  await page.route('**/assignment/recommendations', async route => {
    await gate
    await route.fulfill({ json: response(route.request().postDataJSON()) })
  })
  const requested = page.waitForRequest('**/assignment/recommendations')
  await query(page); await requested
  await page.locator('#meeting-select').selectOption('m2')
  const completed = page.waitForResponse('**/assignment/recommendations')
  release(); await completed
  await expect(panelOf(page).getByLabel('待分配故事')).toHaveValue('')
  await expect(panelOf(page).getByRole('region', { name: '候选结果' })).toHaveCount(0)
})
test('Assignment failed story directory can be reloaded', async ({ page }) => {
  await setup(page, 'admin', false)
  await expect(page.getByRole('button', { name: '打开分配候选' })).toBeVisible()
  await page.route('**/api/stories', route => route.fulfill({ status: 503, json: {} }), { times: 1 })
  await page.getByRole('button', { name: '打开分配候选' }).click()
  await expect(panelOf(page).getByRole('alert')).toContainText('故事目录加载失败')
  await panelOf(page).getByRole('button', { name: '重试故事目录' }).click()
  await fill(page); await query(page)
  await expect(panelOf(page).getByRole('region', { name: '候选结果' })).toBeVisible()
})
