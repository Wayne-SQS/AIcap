import { test, expect } from '@playwright/test'

const insights = (overrides = {}) => ({
  project_id: 'P001', basis: 'story_and_task_specification_fields', as_of: '2026-10-23',
  stories_total: 3, open_tasks: 2,
  limitations: '仅依据库内故事与任务的结构化字段定位规范缺口；未接入真实代码、CI 测试结果与文档仓库前，不评估代码/文档/测试的真实质量，也不虚构覆盖率结论。',
  findings: [
    { kind: 'story_missing_acceptance', source_code: 'US2', source_name: '报表导出', detail: '故事未填写验收条件，完成后无法按 DoD 客观验收。', suggestion: '补齐可验证的验收条件。' },
    { kind: 'task_without_story', source_code: 'T3', source_name: '联调脚本', detail: '开发任务未关联任何故事，工作量无法追溯到需求。', suggestion: '关联到具体故事。' }
  ],
  improvements: [],
  evidence: {
    code_activity: { commits_total: 42, commits_last_14d: 6, prs_total: 3, reviews_total: 5, test_commits: 9, note: '真实事件计数' },
    ci: {
      available: true, runs_total: 2, last_conclusion: 'failure', pass_rate: 50,
      last_failed: { run_name: 'CI', run_number: 21 }, note: '仅复述运行结论',
      coverage: { available: true, instruction_pct: 70, instruction_covered: 700, instruction_missed: 300, line_pct: 70 },
      failed_tests: { available: true, failed_tests_total: 3, failed_tests: ['com.aicap.contract.FooTest.a', 'com.aicap.contract.FooTest.b'] }
    },
    repository: { available: true, repo: 'Wayne-SQS/AIcap', default_branch: 'main', has_ci_workflow: true, has_tests: true, test_file_count: 137, has_docs: true }
  },
  ...overrides
})

test('quality card shows specification gaps with real evidence and honesty limits', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path.endsWith('/quality-analysis')) body = insights()
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  await expect(card).toContainText('识别到 2 项规范缺口')
  await expect(card).toContainText('US2 · 报表导出：故事未填写验收条件')
  await expect(card).toContainText('T3 · 联调脚本：开发任务未关联任何故事')
  await expect(card).toContainText('不虚构覆盖率结论')
  await expect(card).toContainText('CI：最近 2 次运行，最近结论 failure，通过率 50%')
  await expect(card).toContainText('覆盖率：指令 70%')
  await expect(card).toContainText('最近失败测试 3 项')
  await expect(card).toContainText('有测试文件 137 个')
})

test('human confirmation creates a linked improvement task and tracks completion', async ({ page }) => {
  let created = false
  let finished = false
  let posted = null
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/auth/users') body = [{ id: 1, role: 'owner', display_name: '负责人' }]
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path === '/api/projects/P001/members') body = [{ user_id: 1, project_role: 'owner' }]
    if (path.endsWith('/quality-analysis')) body = insights({
      findings: [insights().findings[0]],
      improvements: created ? [{ source_code: 'US2', finding_kind: 'story_missing_acceptance', improvement_task_code: 'T99', improvement_status: finished ? 2 : 0, improvement_progress: finished ? 100 : 0 }] : []
    })
    if (path.endsWith('/US2/improvements')) {
      posted = route.request().postDataJSON()
      created = true
      body = { source_code: 'US2', finding_kind: 'story_missing_acceptance', improvement_task_code: 'T99', improvement_status: 0 }
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  await card.getByRole('button', { name: '确认并创建改进任务' }).click()
  await card.getByLabel('工时').fill('2')
  await card.getByLabel('开始周').fill('5')
  await card.getByLabel('结束周').fill('5')
  await card.getByRole('button', { name: '确认创建' }).click()
  await expect(card).toContainText('US2 → T99：待办')
  expect(posted).toEqual({ finding_kind: 'story_missing_acceptance', owner_id: 1, hours: 2, week_start: 5, week_end: 5 })
  finished = true
  await card.getByRole('button', { name: '刷新分析' }).click()
  await expect(card).toContainText('US2 → T99：已完成')
})
