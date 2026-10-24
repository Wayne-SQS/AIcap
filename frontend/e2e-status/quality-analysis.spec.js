import { test, expect } from '@playwright/test'

const insights = (overrides = {}) => ({
  project_id: 'P001', basis: 'story_task_specification_and_quality_evidence', as_of: '2026-10-23',
  stories_total: 3, open_tasks: 2,
  limitations: '依据库内故事/任务结构化字段、已提交测试集的边界覆盖与可获取的 CI/仓库证据定位质量缺口；未获取的项不作推断，不虚构覆盖率结论。',
  findings: [
    {
      kind: 'story_missing_acceptance', category: '需求规范', source_code: 'US2', source_name: '报表导出',
      location: '故事 US2 · 报表导出',
      detail: '故事未填写验收条件，完成后无法按 DoD 客观验收。', suggestion: '补齐可验证的验收条件。'
    },
    {
      kind: 'task_without_story', category: '任务追溯', source_code: 'T3', source_name: '联调脚本',
      location: '任务 T3 · 联调脚本',
      detail: '开发任务未关联任何故事，工作量无法追溯到需求。', suggestion: '关联到具体故事。'
    }
  ],
  test_quality: { sets_total: 0, cases_total: 0, boundaries_total: 8, sets: [], note: '仅按用例名称/显式边界类别判断覆盖，不执行测试。' },
  improvements: [],
  evidence: {
    code_activity: { commits_total: 42, commits_last_14d: 6, prs_total: 3, reviews_total: 5, test_commits: 9, note: '真实事件计数' },
    ci: {
      available: true, runs_total: 2, runs_cancelled: 0, last_conclusion: 'success', pass_rate: 50,
      last_run: { run_number: 22, head_sha: 'abcdef123456', conclusion: 'success', html_url: 'https://github.com/Wayne-SQS/AIcap/actions/runs/22' },
      last_failed: { run_name: 'CI', run_number: 21 }, note: '仅复述运行结论',
      coverage: { available: true, instruction_pct: 70, instruction_covered: 700, instruction_missed: 300, line_pct: 70 },
      failed_tests: { available: true, failed_tests_total: 3, run_number: 21, created_at: '2026-10-23', failed_tests: ['com.aicap.contract.FooTest.a', 'com.aicap.contract.FooTest.b'] }
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
  await expect(card).toContainText('识别到 2 项质量缺口')
  await expect(card).toContainText('［需求规范］故事 US2 · 报表导出：故事未填写验收条件')
  await expect(card).toContainText('［任务追溯］任务 T3 · 联调脚本：开发任务未关联任何故事')
  await expect(card).toContainText('不虚构覆盖率结论')
  await expect(card).toContainText('CI：最近 2 次已定论运行（另有 0 次取消），最近结论 success，通过率 50%')
  await expect(card).toContainText('最近已完成运行 #22（abcdef1，success）')
  await expect(card).toContainText('历史最近失败：CI #21')
  await expect(card).toContainText('Java 后端覆盖率（JaCoCo）：指令 70%')
  await expect(card).toContainText('历史最近失败运行的测试 3 项（run #21')
  await expect(card).toContainText('已发现测试文件 137 个')
})

test('truncated repository tree keeps absent evidence unknown', async ({ page }) => {
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
    if (path.endsWith('/quality-analysis')) body = insights({ evidence: {
      repository: { available: true, repo: 'Wayne-SQS/AIcap', default_branch: 'main',
        truncated: true, has_ci_workflow: null, has_tests: true, test_file_count: 3, has_docs: null }
    } })
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  await expect(card).toContainText('已发现测试文件 3 个')
  await expect(card).toContainText('CI 配置未在已返回的文件树中找到')
  await expect(card).toContainText('文档未在已返回的文件树中找到')
  await expect(card).toContainText('GitHub 文件树已截断，数量只是下界')
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

test('submitted test set locates missing boundary cases and feeds the analysis card', async ({ page }) => {
  let submitted = false
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
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path.endsWith('/quality-analysis/test-sets')) {
      posted = route.request().postDataJSON()
      submitted = true
      body = {
        id: 3, code: 'TS3', name: '登录边界测试集', source: 'test_code', total: 2,
        cases: [{ name: '用户名为空时登录失败', boundary_kind: null }, { name: '无权限用户访问返回 403', boundary_kind: null }],
        covered_boundaries: ['empty_value', 'permission'],
        missing_boundaries: ['0 值与负数', '上下限与极值', '重复提交与幂等', '超时、并发与重试', '非法格式与超长输入', '大数据量与批量']
      }
    } else if (path.endsWith('/quality-analysis')) {
      body = insights(submitted ? {
        test_quality: {
          sets_total: 1, cases_total: 2, boundaries_total: 8,
          sets: [{
            id: 3, code: 'TS3', name: '登录边界测试集', source: 'test_code', total: 2,
            covered_boundaries: ['empty_value', 'permission'],
            missing_boundaries: ['0 值与负数', '上下限与极值', '重复提交与幂等', '超时、并发与重试', '非法格式与超长输入', '大数据量与批量']
          }],
          note: '仅按用例名称/显式边界类别判断覆盖，不执行测试。'
        },
        findings: [{
          kind: 'test_missing_boundary_cases', category: '测试质量', source_code: 'TS3', source_name: '登录边界测试集',
          location: '测试集 TS3 · 登录边界测试集',
          detail: '已提交 2 条用例，覆盖 2/8 类边界场景；缺少：0 值与负数、上下限与极值。',
          suggestion: '补充覆盖遗漏边界的用例并重新提交测试集后，再确认关闭该缺口。'
        }]
      } : {})
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  await expect(card).toContainText('提交测试集（测试用例/测试代码/执行报告），检查遗漏的边界场景')
  await card.getByLabel('测试集名称').fill('登录边界测试集')
  await card.getByLabel('来源').selectOption('test_code')
  await card.getByLabel('用例（每行一条）').fill('用户名为空时登录失败\n无权限用户访问返回 403')
  await card.getByRole('button', { name: '提交并分析' }).click()

  expect(posted).toEqual({
    name: '登录边界测试集', source: 'test_code',
    cases: [{ name: '用户名为空时登录失败' }, { name: '无权限用户访问返回 403' }]
  })
  await expect(card).toContainText('已提交 TS3：覆盖 2 类边界场景，遗漏 6 类')
  await expect(card).toContainText('已提交测试集 1 份、用例 2 条；边界类别共 8 类。')
  await expect(card).toContainText('TS3 · 登录边界测试集：2 条用例，覆盖 2/8 类边界，遗漏 0 值与负数、上下限与极值')
  await expect(card).toContainText('［测试质量］测试集 TS3 · 登录边界测试集：已提交 2 条用例，覆盖 2/8 类边界场景')
})

const codeQuality = {
  available: true, sufficient: true, persisted: true, repo: 'aicap/demo', branch: 'main',
  total: 68, grade: '中', risk_level: 'high', verdict: '代码质量中等，测试密度是主要短板。',
  sample_note: '抽样 24 个主代码文件，仅静态规则，不运行测试。',
  dimensions: [
    { key: 'test_density', name: '测试密度', score: 40, weight: 20 },
    { key: 'naming', name: '命名', score: 80, weight: 12 }
  ],
  findings: [{
    severity: 'blocking', category: 'test_density', title: '主代码缺少对应测试',
    impact: '回归时无法自动发现行为变化。', action: '为 Demo 的业务分支补充单元测试。',
    evidence: [{ path: 'src/main/java/Demo.java', line: 42 }], recoverable_points: 12
  }],
  strengths: ['命名'], limitations: '评分基于抽样静态规则，需人工复核后确认。'
}

test('code quality evaluation becomes a located, confirmable gap', async ({ page }) => {
  let evaluated = false
  let created = false
  let posted = null
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname)
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path === '/api/projects/P001/members') body = [{ user_id: 1, project_role: 'owner' }]
    if (path.endsWith('/quality-analysis/code-quality')) { evaluated = true; body = codeQuality }
    if (path.endsWith('/CQ:test_density/improvements')) {
      posted = route.request().postDataJSON(); created = true
      body = { source_code: 'CQ:test_density', finding_kind: 'code_quality_dimension_gap', improvement_task_code: 'T88', improvement_status: 0 }
    } else if (path.endsWith('/quality-analysis')) {
      body = insights(evaluated ? {
        code_quality: codeQuality,
        findings: [...insights().findings, {
          kind: 'code_quality_dimension_gap', category: '测试质量', source_code: 'CQ:test_density', source_name: '测试密度 · aicap/demo',
          location: '仓库 aicap/demo（main） · 测试密度（总分 68 / 中）',
          detail: '主代码缺少对应测试（严重度 阻断）；影响：回归时无法自动发现行为变化。证据：src/main/java/Demo.java:42。',
          suggestion: '为 Demo 的业务分支补充单元测试；修复后约可回收 12 分。评分基于抽样静态规则，需人工复核后确认。'
        }],
        improvements: created ? [{ source_code: 'CQ:test_density', finding_kind: 'code_quality_dimension_gap', improvement_task_code: 'T88', improvement_status: 0, improvement_progress: 0 }] : []
      } : {})
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  await card.getByRole('button', { name: '评估代码质量' }).click()

  await expect(card).toContainText('识别到 3 项质量缺口')
  await expect(card).toContainText('［测试质量］仓库 aicap/demo（main） · 测试密度（总分 68 / 中）')
  await expect(card).toContainText('证据：src/main/java/Demo.java:42')
  await expect(card.locator('.qa-score')).toContainText('68')

  await card.locator('.qa-score').getByRole('button', { name: '确认并创建改进任务' }).click()
  await card.getByLabel('工时').fill('3')
  await card.getByLabel('开始周').fill('2')
  await card.getByLabel('结束周').fill('2')
  await card.getByRole('button', { name: '确认创建' }).click()

  expect(posted).toEqual({ finding_kind: 'code_quality_dimension_gap', owner_id: 1, hours: 3, week_start: 2, week_end: 2 })
  await expect(card).toContainText('CQ:test_density → T88：待办')
  await expect(card.locator('.qa-score')).toContainText('已确认 · T88')
})

test('persisted semantic review is replayed and its gap can be confirmed', async ({ page }) => {
  let created = false
  let posted = null
  const review = {
    scope: 'readme', available: true, persisted: true, score: 55, grade: '中',
    verdict: 'README 只写了项目简介，缺安装与运行步骤。', reader_can_act: false, confidence: 'medium',
    issues: [{ quote: '本项目用于管理研发过程', problem: '没有说明如何启动服务', verified: true }],
    missing: ['安装依赖的命令', '启动服务的命令'], unverified_quotes: 1,
    strengths: [], limitations: '本条由模型判断，引用已回原文校验，需人工复核后确认。'
  }
  await page.addInitScript(() => {
    localStorage.setItem('aiguanli_token', 'fixture-token')
    localStorage.setItem('aiguanli-current-project', 'P001')
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname)
    let body = []
    if (path === '/api/health') body = { status: 'ok' }
    if (path === '/api/auth/me') body = { id: 1, role: 'owner', display_name: '负责人' }
    if (path === '/api/projects') body = [{ id: 'P001', name: '测试项目', can_write: true, can_manage: true }]
    if (path === '/api/projects/P001/members') body = [{ user_id: 1, project_role: 'owner' }]
    if (path.endsWith('/SEM:readme/improvements')) {
      posted = route.request().postDataJSON(); created = true
      body = { source_code: 'SEM:readme', finding_kind: 'readme_not_actionable', improvement_task_code: 'T77', improvement_status: 0 }
    } else if (path.endsWith('/quality-analysis')) {
      body = insights({
        semantic_reviews: { readme: review },
        findings: [...insights().findings, {
          kind: 'readme_not_actionable', category: '文档质量', source_code: 'SEM:readme', source_name: 'README 可操作性',
          location: 'README · 语义评审 55 分',
          detail: 'README 只写了项目简介，缺安装与运行步骤。读者无法照做；语义评审仅 55 分（README）；模型引用有 1 条未能在原文核实，需谨慎采信；问题：没有说明如何启动服务',
          suggestion: '补齐以下正文后重跑语义评审：安装依赖的命令、启动服务的命令。本条由模型判断，引用已回原文校验，需人工复核后确认。'
        }],
        improvements: created ? [{ source_code: 'SEM:readme', finding_kind: 'readme_not_actionable', improvement_task_code: 'T77', improvement_status: 0, improvement_progress: 0 }] : []
      })
    }
    await route.fulfill({ json: body })
  })
  await page.goto('/#/projects/P001/overview')
  const card = page.getByRole('region', { name: '项目质量分析' })
  const sem = card.locator('.qa-sem')
  await expect(sem).toContainText('55')
  await expect(sem).toContainText('已留档')
  await expect(sem).toContainText('可照做：否')
  await expect(sem).toContainText('安装依赖的命令')
  await expect(card).toContainText('［文档质量］README · 语义评审 55 分')

  await sem.getByRole('button', { name: '确认并创建改进任务' }).click()
  await card.getByLabel('工时').fill('2')
  await card.getByLabel('开始周').fill('4')
  await card.getByLabel('结束周').fill('4')
  await card.getByRole('button', { name: '确认创建' }).click()

  expect(posted).toEqual({ finding_kind: 'readme_not_actionable', owner_id: 1, hours: 2, week_start: 4, week_end: 4 })
  await expect(card).toContainText('SEM:readme → T77：待办')
  await expect(sem).toContainText('已确认 · T77')
})
