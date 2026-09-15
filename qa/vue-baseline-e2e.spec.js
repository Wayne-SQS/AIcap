import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/**
 * 爱管理 · 当前基线前端 E2E 回归套件(FE-XXX-NN)
 *
 * 被测对象:Vue3 + Vite + Pinia 前端(frontend/,hash 路由,8 视图)
 *          + Java Spring Boot 3.5.3 后端(java-backend/,8080,开发库 AIcap)
 * 数据基线:US01–US37 故事 / T01–T16 任务 / 5 名真实成员 / 5 条成员画像
 * 账号(密码均为 123456):李锐铭=admin、高思晗=owner、孙秋实=member、罗子涵=member、成员5=viewer
 *
 * 写法约定:
 *  - 全部定位基于 Vue 源码里真实存在的 id/class(见 frontend/src/views/*、components/**),
 *    不沿用 qa/ 下针对 legacy/index.html + FastAPI 的旧 spec(qa/ui-e2e.spec.js 等已归档失效)。
 *  - 期望值优先取自后端真实接口(GET /api/...),避免写死数字导致基线漂移后误报。
 *  - 所有写操作都在用例内唯一命名并自清理;绝不删除/修改种子 US01–US37、T01–T16、5 个用户、
 *    5 条成员画像(唯一的例外是 FE-BRD-04/FE-GNT-03/FE-MBR-04 的"改一项再改回",结束时逐字段校验还原)。
 *
 * 运行:cd qa && npx playwright test
 */

const API = process.env.QA_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = '123456'

const ADMIN = '李锐铭'
const OWNER = '高思晗'
const MEMBER = '孙秋实'
const VIEWER = '成员5'

/** 唯一命名后缀:便于识别 QA 造的数据,便于失败后人工清理 */
let seq = 0
function uniq(tag) {
  seq += 1
  return `${tag}-${Date.now().toString(36)}-${seq}-${Math.random().toString(36).slice(2, 6)}`
}

/* ==================================================================================
 * 公共助手
 * ================================================================================== */

function authHeaders(token) {
  return { Authorization: 'Bearer ' + token }
}

/** 后端 API 登录,直接拿 token(用于造数/校验/清理,不经过 UI) */
async function apiLogin(request, username = ADMIN) {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username, password: PASSWORD }
  })
  expect(res.status(), `API 登录 ${username} 应返回 200`).toBe(200)
  return (await res.json()).access_token
}

/** 读接口:断言 200 并返回 JSON */
async function apiGet(request, token, apiPath) {
  const res = await request.get(API + apiPath, { headers: authHeaders(token) })
  expect(res.status(), `GET ${apiPath} 应返回 200`).toBe(200)
  return res.json()
}

/** 写接口:返回 { status, body },由调用方决定断言 */
async function apiSend(request, token, method, apiPath, data) {
  const res = await request.fetch(API + apiPath, {
    method,
    headers: authHeaders(token),
    data
  })
  let body = null
  try { body = await res.json() } catch { /* 204/空响应 */ }
  return { status: res.status(), body }
}

/** 清理助手:删除用例自己造的故事,已不存在也视为清理成功 */
async function cleanupStory(request, token, id) {
  if (!id) return
  const res = await request.delete(`${API}/api/stories/${id}?undone=keep`, { headers: authHeaders(token) })
  expect([200, 404], `清理故事 ${id} 应成功或已不存在`).toContain(res.status())
}

/** 清理助手:删除用例自己造的需求池条目 */
async function cleanupPool(request, token, id) {
  if (!id) return
  const res = await request.delete(`${API}/api/pool/${id}`, { headers: authHeaders(token) })
  expect([200, 404], `清理需求池条目 ${id} 应成功或已不存在`).toContain(res.status())
}

/** 打开页面并在登录弹窗里完成登录(默认 admin) */
async function loginAndOpen(page, hash = '/#/board', user = ADMIN) {
  await page.goto(hash)
  const dialog = page.locator('#login')
  await expect(dialog, '后端在线时 bootstrap 应自动弹出登录弹窗').toBeVisible()
  await page.locator('#login-user').fill(user)
  await page.locator('#login-pass').fill(PASSWORD)
  await page.locator('#login-form button[type=submit]').click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('#user-chip')).toBeVisible()
  await expect(page.locator('#mode-chip')).toHaveText('在线 · 已连接后端')
}

/** 已登录后的站内跳转(整页刷新,token 在 localStorage 里,bootstrap 会自动恢复会话) */
async function openView(page, hash) {
  await page.goto(hash)
  await expect(page.locator('#user-chip')).toBeVisible()
  await expect(page.locator('#mode-chip')).toHaveText('在线 · 已连接后端')
}

/** 看板:切到「状态看板」页签 */
async function openBoardTab(page) {
  await page.locator('#boardtab').click()
  await expect(page.locator('#boardpanel')).toBeVisible()
}

/** 看板:按标题搜索(清空时传空串) */
async function searchBoard(page, text) {
  await page.locator('#search').fill(text)
}

/** 看板:卡片上的子任务血缘徽章(▣ x/y · %) */
async function cardBadge(page, storyId) {
  const badge = page.locator(`#board .card[data-id="${storyId}"] .subprog`)
  return (await badge.count()) ? (await badge.textContent()).trim() : null
}

/** 打开故事编辑弹窗并返回弹窗定位器 */
async function openStoryEditor(page, storyId, fromBoard = true) {
  if (fromBoard) {
    await page.locator(`#board .card[data-id="${storyId}"]`).click()
  }
  const dialog = page.locator('#editor')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('#editid')).toContainText(storyId)
  return dialog
}

/** 前端血缘口径(project.js cardPct/groupPct)在测试侧独立复算,用于交叉校验界面百分比 */
function featureSubs(tasks, cardId) {
  return tasks.filter(t => t.kanban_card_id === cardId && (t.task_type || 'feature') === 'feature')
}

/** 任务状态口径(project.js taskStatusKey)在测试侧复算 */
function taskStatusKey(t) {
  if (t.status === 3) return 'cancelled'
  if (t.blocked) return 'blocked'
  if (t.status === 2 || t.progress >= 100) return 'done'
  if (t.status === 1 || t.progress > 0) return 'doing'
  return 'todo'
}

function expectedWeightedPct(stories, tasks) {
  let doneW = 0
  let totalW = 0
  for (const s of stories) {
    const subs = featureSubs(tasks, s.id)
    if (subs.length) {
      totalW += subs.reduce((a, t) => a + (t.estimated_hours ?? t.hours), 0)
      doneW += subs.filter(t => t.status === 2).reduce((a, t) => a + (t.estimated_hours ?? t.hours), 0)
    } else {
      totalW += 1
      doneW += s.status === 2 ? 1 : (s.status === 1 ? 0.5 : 0)
    }
  }
  return totalW ? Math.round(doneW / totalW * 100) : 0
}

/* ==================================================================================
 * 故事地图「马甲」口径:在前端 data/planCalendar.js 之外独立复算一遍,
 * 用后端真实数据推导期望分档,避免把 10/11/11/3/2 这类数字写死(基线漂移会误报)。
 * ================================================================================== */

/** QA 注入的「今天」(前端 window.__AICAP_TODAY__,与 __AICAP_API_BASE__ 同一注入约定)。
 *  不注入的话这套断言会随真实日期漂移:9 月里「逾期 10」到 10 月就变成 20。 */
const QA_TODAY = '2026-09-15'
const QA_NOW_WEEK = 3

/** 每 2 周一个 Sprint:W1–W2=S1、W3–W4=S2、W5–W6=S3(与 seed.js derivedTaskSprints 同式) */
const SPRINT_END_WEEK = { 1: 2, 2: 4, 3: 6 }
const URGENCY_GLYPH = { overdue: '!', due: '▲', soon: '·', later: '○', roadmap: '?', done: '✓' }

/** 故事的截止周:优先取所挂子任务的最晚计划周,无子任务退回切片末周;Sprint 4+ 无排期 */
function deadlineWeekOf(story, tasks) {
  const subs = featureSubs(tasks, story.id)
  if (subs.length) return Math.max(...subs.map(t => t.week_end))
  return SPRINT_END_WEEK[story.sprint] ?? null
}

/** 紧急度档位:g = 截止周 − 当前周;<0 逾期、=0 本周到期、1–2 临近、≥3 宽松 */
function urgencyKeyOf(story, tasks, nowWeek = QA_NOW_WEEK) {
  if (story.status === 2) return 'done'
  const d = deadlineWeekOf(story, tasks)
  if (d == null) return 'roadmap'
  const gap = d - nowWeek
  if (gap < 0) return 'overdue'
  if (gap === 0) return 'due'
  if (gap <= 2) return 'soon'
  return 'later'
}

/** 注入「今天」并把看板打开到故事地图(默认 admin 在线登录) */
async function openMapWithToday(page, today = QA_TODAY, user = ADMIN) {
  await page.addInitScript(d => { window.__AICAP_TODAY__ = d }, today)
  await loginAndOpen(page, '/#/board', user)
  await expect(page.locator('#map .card').first()).toBeVisible()
}

/** 生成一个最小但结构合法的 MP3(ID3v2 头 + MPEG1 Layer III 静音帧),用于音频上传用例 */
function writeTempMp3(fileName) {
  const id3v2 = Buffer.from([0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
  const frame = Buffer.alloc(417)                                  // 128kbps/44.1kHz 帧长 = 417 字节
  Buffer.from([0xff, 0xfb, 0x90, 0x00]).copy(frame, 0)             // MPEG1 Layer3 帧同步头
  const frames = Buffer.concat(Array.from({ length: 10 }, () => frame))
  const file = path.join(os.tmpdir(), fileName)
  fs.writeFileSync(file, Buffer.concat([id3v2, frames]))
  return file
}

/* ==================================================================================
 * FE-AUTH · 登录 / 会话 / 权限
 * ================================================================================== */
test.describe('FE-AUTH 登录与会话', () => {
  test('[FE-AUTH-01] 李锐铭登录成功:顶栏显示已连接后端与真实身份', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)

    await loginAndOpen(page, '/#/overview', ADMIN)

    // 顶栏:连接状态 + 真实姓名 + 角色
    await expect(page.locator('#mode-chip')).toHaveText('在线 · 已连接后端')
    await expect(page.locator('#user-chip')).toHaveText('李锐铭 · 管理员')
    // 面包屑跟随 hash 路由
    await expect(page.locator('#crumb-name')).toHaveText('项目总览')
    // 数据确实来自后端(与 API 基线一致),不是离线种子凑数
    const users = await apiGet(request, token, '/api/auth/users')
    expect(users.find(u => u.display_name === ADMIN).role).toBe('admin')
    await expect(page.locator('#toast')).toContainText('欢迎，李锐铭')
    // 在线模式的同步提示只在看板页脚暴露(#savehint 属于 BoardView)
    await openView(page, '/#/board')
    await expect(page.locator('#savehint')).toContainText('已连接后端 · 数据实时同步到服务器')
    await expect(page.locator('#hintcount')).toContainText('US01–US37 基线')
  })

  test('[FE-AUTH-02] 错误密码登录被拒绝:停留登录弹窗且后端返回 401', async ({ page, request }) => {
    // 后端契约:密码错误 = 401「用户名或密码错误」
    const raw = await request.post(`${API}/api/auth/login`, {
      data: { username: ADMIN, password: 'wrong-password' }
    })
    expect(raw.status(), '错误密码应被后端拒绝').toBe(401)
    expect((await raw.json()).detail).toBe('用户名或密码错误')

    await page.goto('/#/overview')
    const dialog = page.locator('#login')
    await expect(dialog).toBeVisible()
    await page.locator('#login-user').fill(ADMIN)
    await page.locator('#login-pass').fill('wrong-password')
    await page.locator('#login-form button[type=submit]').click()

    // 前端表现:登录失败的提示必须保留后端原因(FE-D01 回归锚点),弹窗保持打开、未建立会话
    await expect(page.locator('#toast')).toContainText('登录失败：用户名或密码错误')
    await expect(page.locator('#toast')).not.toContainText('未登录')
    await expect(page.locator('#toast')).not.toContainText('登录已过期')
    console.log('[FE-AUTH-02] 前端实际提示文案 = ' + (await page.locator('#toast').textContent()).trim())
    await expect(dialog).toBeVisible()
    await expect(page.locator('#user-chip')).toHaveCount(0)
    expect(await page.evaluate(() => localStorage.getItem('aiguanli_token')), '失败登录不应写入 token').toBeNull()
  })

  test('[FE-AUTH-03] 只读查看者(成员5)身份可见、前端写操作被拦截、写接口返回 403', async ({ page, request }) => {
    await loginAndOpen(page, '/#/board', VIEWER)

    // 身份:display_name 为「只读查看者」,角色显示为「查看者」
    await expect(page.locator('#user-chip')).toHaveText('只读查看者 · 查看者')

    // 前端拦截(FE-D04 修复后):写入口直接呈禁用态并给出原因,不再"可点但被 toast 拦下"
    await expect(page.locator('#new')).toBeDisabled()
    await expect(page.locator('#new')).toHaveAttribute('title', /只读/)
    await expect(page.locator('#editor')).toBeHidden()

    // 前端拦截 2:新增需求同样是禁用态,表单不展开
    await openView(page, '/#/pool')
    await expect(page.locator('#pool-add')).toBeDisabled()
    await expect(page.locator('#pool-add')).toHaveAttribute('title', /只读/)
    await expect(page.locator('#pool-form')).toBeHidden()

    // FE-D02 修复后:viewer 的真名(display_name)与「成员5」两种叫法都能登录到同一账号
    const aliasLogin = await request.post(`${API}/api/auth/login`, {
      data: { username: '只读查看者', password: PASSWORD }
    })
    expect(aliasLogin.status(), 'viewer 应能用显示名「只读查看者」登录').toBe(200)
    const aliasUser = await request.get(`${API}/api/auth/me`, {
      headers: authHeaders((await aliasLogin.json()).access_token)
    })
    expect(aliasUser.status()).toBe(200)
    expect((await aliasUser.json()).display_name).toBe('只读查看者')

    // 后端拦截:viewer 的 token 调写接口必须 403(只读断言,不落任何数据)
    const viewerToken = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
    expect(viewerToken, 'viewer 登录后应持有 token').toBeTruthy()
    const created = await apiSend(request, viewerToken, 'POST', '/api/stories', {
      title: uniq('QA-VIEWER-SHOULD-NOT-EXIST'),
      description: 'viewer 不允许创建',
      acceptance: '应被 403 拒绝',
      priority: 'Could', sprint: 1, activity: 2, status: 0, owner_id: null
    })
    expect(created.status, 'viewer 调 POST /api/stories 应 403').toBe(403)

    const poolCreate = await apiSend(request, viewerToken, 'POST', '/api/pool', {
      title: uniq('QA-VIEWER-POOL-SHOULD-NOT-EXIST'), description: 'viewer 不允许创建', source: 'QA 权限回归'
    })
    expect(poolCreate.status, 'viewer 调 POST /api/pool 应 403').toBe(403)
  })

  test('[FE-AUTH-04] 退出登录回到未登录态,点击状态胶囊可重新唤起登录', async ({ page }) => {
    await loginAndOpen(page, '/#/overview', OWNER)
    await expect(page.locator('#user-chip')).toHaveText('高思晗 · 负责人')

    await page.locator('#logout-btn').click()

    // 回退到未登录态:身份与退出按钮消失,连接胶囊变离线演示数据
    await expect(page.locator('#toast')).toContainText('已退出登录，回到演示数据')
    await expect(page.locator('#user-chip')).toHaveCount(0)
    await expect(page.locator('#logout-btn')).toHaveCount(0)
    await expect(page.locator('#mode-chip')).toHaveText('离线 · 演示数据(点击重连)')
    expect(await page.evaluate(() => localStorage.getItem('aiguanli_token'))).toBeNull()

    // 点击胶囊重连 → 再次检测到后端并弹出登录弹窗
    await page.locator('#mode-chip').click()
    await expect(page.locator('#login')).toBeVisible()
    await expect(page.locator('#mode-chip')).toHaveText('在线 · 未登录')
  })
})

/* ==================================================================================
 * FE-OVW · 项目总览
 * ================================================================================== */
test.describe('FE-OVW 项目总览', () => {
  test('[FE-OVW-01] 概览统计卡与后端基线一致(故事 37 / 任务 16 / 成员 05),Sprint 分片含 4+ 且求和自洽', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const [stories, tasks, users, dash] = await Promise.all([
      apiGet(request, token, '/api/stories'),
      apiGet(request, token, '/api/tasks'),
      apiGet(request, token, '/api/auth/users'),
      apiGet(request, token, '/api/dashboard')
    ])
    expect(stories.length, '故事基线应为 US01–US37 共 37 条').toBe(37)
    expect(tasks.length, '任务基线应为 T01–T16 共 16 条').toBe(16)
    expect(users.length, '成员应为 5 名').toBe(5)

    await loginAndOpen(page, '/#/overview', ADMIN)

    await expect(page.locator('#ov-total')).toHaveText(String(stories.length).padStart(2, '0'))
    await expect(page.locator('#ov-tasks')).toHaveText(String(tasks.length))

    const stats = page.locator('.stats .stat')
    await expect(stats.filter({ hasText: '故事总数' }).locator('span.small')).toHaveText('个用户故事')
    await expect(stats.filter({ hasText: '团队成员' }).locator('strong'))
      .toHaveText(String(users.length).padStart(2, '0'))
    await expect(stats.filter({ hasText: '团队成员' }).locator('span.small')).toHaveText('人 · 含只读查看者')
    // 待关注/阻塞:必须是真实 blocked 计数,不得是写死常量
    await expect(stats.filter({ hasText: '待关注' }).locator('strong'))
      .toHaveText(String(tasks.filter(t => t.blocked).length).padStart(2, '0'))

    /* Sprint 分片:必须覆盖全部故事。此前用 SPRINTS(仅 3 片)按 sprint===i+1 过滤,
       Sprint 4+ 的 3 条故事不进任何卡片 → 卡片故事数之和 34 ≠ 总数 37。
       现统一到 MAP_SPRINTS 4 片,与故事地图切片、看板筛选下拉、后端 by_sprint 的 4 桶一致。 */
    const cards = page.locator('#sprint-grid .sprint')
    await expect(cards).toHaveCount(4)
    await expect(cards.last().locator('h3')).toHaveText('Sprint 4+')
    const cardCounts = await cards.locator('.tagline').evaluateAll(
      els => els.map(e => Number(/·\s*(\d+)\s*个故事/.exec(e.textContent)[1])))
    expect(cardCounts.reduce((a, b) => a + b, 0),
      '各 Sprint 卡片故事数之和必须等于故事总数(不得漏掉 Sprint 4+)').toBe(stories.length)
    // 每片的故事数与后端 by_sprint 的 total 逐桶对齐(1..4;第 4 桶在基线上等价于 sprint>=4)
    const bucketTotal = i => dash.by_sprint.find(b => b.sprint === i + 1).total
    expect(cardCounts.slice(0, 3)).toEqual([bucketTotal(0), bucketTotal(1), bucketTotal(2)])
    expect(cardCounts[3],
      'Sprint 4+ 卡片数应等于后端第 4 桶').toBe(bucketTotal(3))
  })

  test('[FE-OVW-02] 完成比例与总完成度为 0–100 数字,且与状态/工时加权口径一致,并与 /api/dashboard 交叉校验', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const [stories, tasks, dash] = await Promise.all([
      apiGet(request, token, '/api/stories'),
      apiGet(request, token, '/api/tasks'),
      apiGet(request, token, '/api/dashboard')
    ])
    const done = stories.filter(s => s.status === 2).length
    const expectedRealtime = Math.round(done / stories.length * 100)
    const expectedWeighted = expectedWeightedPct(stories, tasks)

    /* 交叉校验:同一页上有两套「完成度」口径,必须各自与权威来源对齐,不允许静默漂移 ——
       ① 纯计数口径(完成比例) 必须等于后端 /api/dashboard 的 total/done/doing/percent;
       ② 工时加权口径(总完成度 / Sprint 卡) 由 project.js groupPct 计算,后端没有对应端点,
          只能按测试侧独立复算的 expectedWeightedPct 校验(见上方断言)。
       此前两套口径都存在于前端、后端也有一个 /api/dashboard,但没有任何测试把它们钉在一起。 */
    expect(dash.total, '后端 dashboard.total 应等于故事列表长度').toBe(stories.length)
    expect(done, '后端 dashboard.done 应等于 status=2 的故事数').toBe(dash.done)
    expect(dash.doing, '后端 dashboard.doing 应等于 status=1 的故事数')
      .toBe(stories.filter(s => s.status === 1).length)
    expect(expectedRealtime, '纯计数口径应与后端 dashboard.percent 一致').toBe(dash.percent)

    await loginAndOpen(page, '/#/overview', ADMIN)

    const realtime = page.locator('#realtime .stat').filter({ hasText: '完成比例' })
    await expect(realtime.locator('strong')).toHaveText(`${dash.percent}%`)
    await expect(realtime.locator('span.small')).toHaveText(`${dash.done} / ${dash.total} 故事`)
    await expect(page.locator('#realtime .stat').filter({ hasText: '进行中' }).locator('strong'))
      .toHaveText(String(dash.doing).padStart(2, '0'))

    const overall = page.locator('#ov-percent')
    await expect(overall).toHaveText(`${expectedWeighted}%`)
    const shown = Number((await overall.textContent()).replace('%', ''))
    expect(Number.isFinite(shown), '总完成度必须是数字').toBeTruthy()
    expect(shown, '总完成度必须落在 0–100').toBeGreaterThanOrEqual(0)
    expect(shown).toBeLessThanOrEqual(100)
    // 进度条宽度与文本口径一致(看板 5% 与总完成度 1% 是两套口径,分别校验)
    const width = await page.locator('#ov-fill').evaluate(el => el.style.width)
    expect(width).toBe(`${expectedWeighted}%`)
  })
})

/* ==================================================================================
 * FE-POOL · 需求池
 * ================================================================================== */
test.describe('FE-POOL 需求池', () => {
  test('[FE-POOL-01] 需求池列表渲染与接口一致,且无数据时显示空态', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const pool = await apiGet(request, token, '/api/pool')

    await loginAndOpen(page, '/#/pool', ADMIN)
    const items = page.locator('#pool-list .pool-item')
    await expect(items).toHaveCount(pool.length)
    await expect(page.locator('#pool-list .empty')).toHaveCount(pool.length ? 0 : 1)
    for (const p of pool) {
      const item = items.filter({ has: page.locator('h3', { hasText: p.title }) })
      await expect(item.locator('.tag')).toHaveText(p.priority)
    }
    await expect(page.locator('#pool-list .mono').first()).toHaveText(pool[0].id)

    /* 空态渲染:当前开发库需求池非空(存在历史冒烟残留),因此用接口拦截至空数组来验证
       「需求池为空」分支 —— 只影响本用例的这一次页面加载,不写任何数据 */
    await page.route('**/api/pool', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '[]'
    }))
    await page.reload()
    await expect(page.locator('#user-chip')).toBeVisible()
    await expect(page.locator('#pool-list .pool-item')).toHaveCount(0)
    await expect(page.locator('#pool-list .empty')).toHaveText('需求池为空')
    await page.unroute('**/api/pool')
  })

  test('[FE-POOL-02] 新增需求出现在列表并可移除清理(唯一命名,不碰既有条目)', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const before = await apiGet(request, token, '/api/pool')
    const title = uniq('QA-POOL-REMOVE')
    const desc = 'QA 回归用例创建,结束前移除'
    let createdId = null

    try {
      await loginAndOpen(page, '/#/pool', ADMIN)
      await expect(page.locator('#pool-list .pool-item')).toHaveCount(before.length)

      await page.locator('#pool-add').click()
      await expect(page.locator('#pool-form')).toBeVisible()
      await page.locator('#pool-title').fill(title)
      await page.locator('#pool-desc').fill(desc)
      await page.locator('#pool-pri').selectOption('Should')
      await page.locator('#pool-save').click()
      await expect(page.locator('#toast')).toContainText('已保存到需求池（服务器）')

      // 列表出现(界面真实渲染)+ 接口真实落库
      await expect(page.locator('#pool-list .pool-item')).toHaveCount(before.length + 1)
      const created = page.locator('#pool-list .pool-item').filter({ has: page.locator('h3', { hasText: title }) })
      await expect(created).toHaveCount(1)
      await expect(created.locator('.tag')).toHaveText('Should')
      await expect(created.locator('.pdesc')).toHaveText(desc)
      await expect(created.locator('.pmeta')).toContainText('手动新增')
      const after = await apiGet(request, token, '/api/pool')
      const mine = after.find(p => p.title === title)
      expect(mine, '新条目应已落库').toBeTruthy()
      createdId = mine.id

      // 移除清理(界面按钮 → 接口删除)
      await page.locator(`#pool-list button[data-drop="${createdId}"]`).click()
      await expect(page.locator('#toast')).toContainText(`${createdId} 已从需求池移除`)
      await expect(page.locator('#pool-list .pool-item')).toHaveCount(before.length)
      await expect(page.locator('#pool-list')).not.toContainText(title)
      const restored = await apiGet(request, token, '/api/pool')
      expect(restored.map(p => p.id).sort()).toEqual(before.map(p => p.id).sort())
      createdId = null
    } finally {
      await cleanupPool(request, token, createdId)
    }
  })

  test('[FE-POOL-03] 需求提升为故事:进入 US38+ 命名空间并可级联删除清理', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const beforePool = await apiGet(request, token, '/api/pool')
    const beforeStories = await apiGet(request, token, '/api/stories')
    const title = uniq('QA-POOL-PROMOTE')
    const created = await apiSend(request, token, 'POST', '/api/pool', {
      title, description: 'QA 回归:验证提升为故事的闭环', source: 'QA 自动化用例', priority: 'Could'
    })
    expect(created.status, '造数:创建需求池条目应 201/200').toBeLessThan(300)
    const poolId = created.body.id
    let storyId = null

    try {
      await loginAndOpen(page, '/#/pool', ADMIN)
      const item = page.locator('#pool-list .pool-item').filter({ has: page.locator('h3', { hasText: title }) })
      await expect(item).toHaveCount(1)

      await page.locator(`#pool-list button[data-promote="${poolId}"]`).click()
      const promote = page.locator('#promote')
      await expect(promote).toBeVisible()
      await expect(promote.locator('.formbody')).toContainText(title)
      // 目标 Sprint 必填 + 负责人可选(未分配)
      await expect(promote.locator('select[name=sprint]')).toHaveValue('')
      await promote.locator('select[name=sprint]').selectOption('1')
      await promote.locator('select[name=owner_id]').selectOption({ label: MEMBER })
      await promote.locator('select[name=activity]').selectOption('2')
      await promote.locator('button[type=submit]').click()
      await expect(promote).toBeHidden()
      await expect(page.locator('#toast')).toContainText('已移入 Sprint 1 · 已选择负责人')

      // 池里消失,故事里出现(US38 起的新编号,名称沿用需求标题)
      await expect(page.locator('#pool-list')).not.toContainText(title)
      const afterStories = await apiGet(request, token, '/api/stories')
      expect(afterStories.length, '故事数应 +1').toBe(beforeStories.length + 1)
      const story = afterStories.find(s => s.title === title)
      expect(story, '提升后的故事应存在').toBeTruthy()
      storyId = story.id
      expect(story.id, '新故事应落在 US38+ 命名空间').toMatch(/^US\d+$/)
      expect(Number(story.id.slice(2))).toBeGreaterThanOrEqual(38)
      expect(story.sprint).toBe(1)
      expect(story.status, '新故事初始状态应为待办').toBe(0)

      // 界面可见:看板里搜到这张新卡
      await openView(page, '/#/board')
      await openBoardTab(page)
      await searchBoard(page, title)
      await expect(page.locator(`#board .card[data-id="${storyId}"]`)).toHaveCount(1)

      // 清理:删除自己造的故事(无子任务,直接删除)
      await cleanupStory(request, token, storyId)
      const finalStories = await apiGet(request, token, '/api/stories')
      expect(finalStories.map(s => s.id).sort()).toEqual(beforeStories.map(s => s.id).sort())
      storyId = null
      const finalPool = await apiGet(request, token, '/api/pool')
      expect(finalPool.map(p => p.id).sort()).toEqual(beforePool.map(p => p.id).sort())
    } finally {
      await cleanupStory(request, token, storyId)
      await cleanupPool(request, token, poolId)
    }
  })

  test('[FE-POOL-04] 必填校验:空标题保存被阻止且不产生新条目', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const before = await apiGet(request, token, '/api/pool')

    await loginAndOpen(page, '/#/pool', ADMIN)
    await page.locator('#pool-add').click()
    await page.locator('#pool-title').fill('')
    await page.locator('#pool-save').click()

    await expect(page.locator('#toast')).toContainText('请填写需求标题')
    await expect(page.locator('#pool-form')).toBeVisible()
    await expect(page.locator('#pool-list .pool-item')).toHaveCount(before.length)
    const after = await apiGet(request, token, '/api/pool')
    expect(after.length, '空标题不应落库').toBe(before.length)

    // 取消能正常收起表单,且仍不产生数据
    await page.locator('#pool-cancel').click()
    await expect(page.locator('#pool-form')).toBeHidden()
  })
})

/* ==================================================================================
 * FE-BRD · 用户故事看板
 * ================================================================================== */
test.describe('FE-BRD 用户故事看板', () => {
  test('[FE-BRD-01] 默认进入故事地图:4 个发布切片(含 Sprint 4+)、Sprint 默认全部、含 US01', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')

    await loginAndOpen(page, '/#/board', ADMIN)

    // 默认为故事地图,状态看板隐藏
    await expect(page.locator('#mappanel')).toBeVisible()
    await expect(page.locator('#boardpanel')).toBeHidden()
    await expect(page.locator('#maptab')).toHaveAttribute('aria-selected', 'true')
    // 默认 Sprint = 全部
    await expect(page.locator('#sprint')).toHaveValue('all')
    await expect(page.locator('#sprint option[value="all"]')).toHaveText('全部 Sprint')
    // 头部口径卡
    await expect(page.locator('#hintcount')).toContainText(`已同步 ${stories.length} 条真实故事（US01–US37 基线）`)

    // 4 个发布切片,第 4 片是 Sprint 4+ 后续路线
    const heads = page.locator('#map .sprinthead')
    await expect(heads).toHaveCount(4)
    await expect(heads.nth(3)).toContainText('Sprint 4+')
    await expect(heads.nth(3)).toContainText('后续路线')

    // 地图卡 = 全部故事,US01 在 Sprint 1 切片里且负责人是真实姓名
    await expect(page.locator('#map .card')).toHaveCount(stories.length)
    const mapIds = await page.locator('#map .card').evaluateAll(els => els.map(e => e.dataset.id))
    expect(mapIds).toContain('US01')
    expect(mapIds).toContain('US37')
    expect(mapIds.filter(id => /^M\d+$/.test(id)), '不应出现旧基线 M 编号').toEqual([])
    const us01 = page.locator('#map .card[data-id="US01"]')
    await expect(us01).toContainText('项目与成员范围')
    await expect(us01.locator('.mapstatus')).toHaveText('已完成 · Sprint 1')
    await expect(us01.locator('.owner')).toContainText('李锐铭')
    // Sprint 4+ 的三条后续路线故事确实落在第 4 片
    const s4 = await page.locator('#map .mapcell.s4 .card').evaluateAll(els => els.map(e => e.dataset.id).sort())
    expect(s4).toEqual(stories.filter(s => s.sprint >= 4).map(s => s.id).sort())
  })

  test('[FE-BRD-02] 卡片血缘徽章为「▣ 完成数/子任务数 · 加权%」,无子任务的卡不显示徽章', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const tasks = await apiGet(request, token, '/api/tasks')
    const sub = id => featureSubs(tasks, id)

    await loginAndOpen(page, '/#/board', ADMIN)
    await openBoardTab(page)

    // US01 挂 T03(16h / 进行中)→ 0/1 · 0%
    const us01 = sub('US01')
    expect(us01.length, 'US01 应有 1 条 feature 子任务').toBeGreaterThan(0)
    const doneCount = us01.filter(t => t.status === 2).length
    const weighted = Math.round(
      us01.filter(t => t.status === 2).reduce((a, t) => a + (t.estimated_hours ?? t.hours), 0) /
      us01.reduce((a, t) => a + (t.estimated_hours ?? t.hours), 0) * 100
    )
    const badge = await cardBadge(page, 'US01')
    expect(badge, 'US01 卡应有血缘徽章').toBeTruthy()
    expect(badge, '徽章格式应为 ▣ x/y · %').toMatch(/^▣ \d+\/\d+ · \d+%$/)
    expect(badge).toBe(`▣ ${doneCount}/${us01.length} · ${weighted}%`)
    // 徽章 title 记录工时加权口径
    await expect(page.locator('#board .card[data-id="US01"] .subprog')).toHaveAttribute('title', /子任务工时加权进度/)

    // US05 无子任务 → 不渲染徽章
    expect(sub('US05').length).toBe(0)
    await expect(page.locator('#board .card[data-id="US05"] .subprog')).toHaveCount(0)
    expect(await cardBadge(page, 'US05')).toBeNull()
  })

  test('[FE-BRD-03] 新建故事(唯一名)出现在看板并可删除清理,不污染基线', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const before = await apiGet(request, token, '/api/stories')
    const title = uniq('QA-BRD-CREATE')
    const description = '作为测试工程师，我希望新建故事能真实落库，以便回归看板写入路径'
    const acceptance = '保存后出现在看板；用例结束前删除'
    let newId = null
    page.on('dialog', d => d.accept())

    try {
      await loginAndOpen(page, '/#/board', ADMIN)
      await page.locator('#new').click()
      const dialog = page.locator('#editor')
      await expect(dialog).toBeVisible()
      await expect(dialog.locator('#editheading')).toHaveText('新建用户故事')
      await expect(dialog.locator('#editid')).toContainText('保存后将生成唯一故事编号')

      await dialog.locator('input[name=title]').fill(title)
      await dialog.locator('textarea[name=description]').fill(description)
      await dialog.locator('textarea[name=acceptance]').fill(acceptance)
      await dialog.locator('select[name=sprint]').selectOption('1')
      await dialog.locator('select[name=owner]').selectOption('')
      await dialog.locator('select[name=activity]').selectOption('2')
      await dialog.locator('select[name=status]').selectOption('0')
      await dialog.locator('button[type=submit]').click()
      await expect(dialog).toBeHidden()
      await expect(page.locator('#toast')).toContainText('已创建 · 已同步到服务器')

      // 真实落库:US38+ 命名空间
      const after = await apiGet(request, token, '/api/stories')
      expect(after.length, '故事数应 +1').toBe(before.length + 1)
      const story = after.find(s => s.title === title)
      expect(story, '新建的故事应存在').toBeTruthy()
      newId = story.id
      expect(Number(newId.slice(2)), '新故事应落在 US38+').toBeGreaterThanOrEqual(38)
      expect(story.description).toBe(description)
      expect(story.acceptance).toBe(acceptance)
      expect(story.owner_id, '未分配应落 null').toBeNull()

      // 界面上出现在「待办」列
      await openBoardTab(page)
      await searchBoard(page, title)
      await expect(page.locator('#board .card')).toHaveCount(1)
      const card = page.locator(`#board .card[data-id="${newId}"]`)
      await expect(card).toBeVisible()
      await expect(card).toContainText(title)
      await expect(card.locator('.cardtop .id')).toHaveText(newId)

      // 删除清理:点卡 → 编辑弹窗 → 删除故事(无子任务,单一确认)
      await card.click()
      const editDialog = page.locator('#editor')
      await expect(editDialog).toBeVisible()
      await expect(editDialog.locator('#editid')).toContainText(newId)
      await editDialog.locator('#del').click()
      await expect(editDialog).toBeHidden()
      await expect(page.locator('#toast')).toContainText(`${newId} 已删除`)
      const finalList = await apiGet(request, token, '/api/stories')
      expect(finalList.map(s => s.id).sort(), '基线应完全复原').toEqual(before.map(s => s.id).sort())
      newId = null
    } finally {
      await cleanupStory(request, token, newId)
    }
  })

  test('[FE-BRD-04] 编辑故事改 Sprint/负责人真实往返:刷新后仍在,再改回', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const users = await apiGet(request, token, '/api/auth/users')
    const liruiming = users.find(u => u.display_name === ADMIN)
    const sunqiushi = users.find(u => u.display_name === MEMBER)
    const title = uniq('QA-BRD-EDIT')
    const created = await apiSend(request, token, 'POST', '/api/stories', {
      title,
      description: '作为测试工程师，我希望编辑故事改动真实保存，以便回归 Sprint/负责人往返',
      acceptance: '刷新页面后仍为改后的值；用例结束前改回并删除',
      priority: 'Could', sprint: 1, activity: 2, status: 0, owner_id: liruiming.id
    })
    expect(created.status, '造数:创建故事应成功').toBeLessThan(300)
    const storyId = created.body.id
    let restored = false

    try {
      await loginAndOpen(page, '/#/board', ADMIN)
      await openBoardTab(page)
      await searchBoard(page, title)

      const dialog = await openStoryEditor(page, storyId)
      await expect(dialog.locator('#editheading')).toHaveText('编辑用户故事')
      await expect(dialog.locator('input[name=title]')).toHaveValue(title)
      await expect(dialog.locator('select[name=sprint]')).toHaveValue('1')
      await expect(dialog.locator('select[name=owner]')).toHaveValue(String(liruiming.id - 1))

      // 改 Sprint 1 → 2、负责人 李锐铭 → 孙秋实
      await dialog.locator('select[name=sprint]').selectOption('2')
      await dialog.locator('select[name=owner]').selectOption(String(sunqiushi.id - 1))
      await dialog.locator('button[type=submit]').click()
      await expect(dialog).toBeHidden()
      await expect(page.locator('#toast')).toContainText('已更新 · 已同步到服务器')

      const patched = (await apiGet(request, token, '/api/stories')).find(s => s.id === storyId)
      expect(patched.sprint, '改后的 Sprint 应落库').toBe(2)
      expect(patched.owner_id, '改后的负责人应落库').toBe(sunqiushi.id)

      // 整页刷新后仍然是改后的值(真实往返,不是只改内存)
      await page.reload()
      await expect(page.locator('#user-chip')).toBeVisible()
      await openBoardTab(page)
      await searchBoard(page, title)
      await expect(page.locator(`#board .card[data-id="${storyId}"] .cardfoot .owner`)).toContainText(MEMBER)
      const reopened = await openStoryEditor(page, storyId)
      await expect(reopened.locator('select[name=sprint]')).toHaveValue('2')
      await expect(reopened.locator('select[name=owner]')).toHaveValue(String(sunqiushi.id - 1))

      // 改回原值并核对逐字段还原
      await reopened.locator('select[name=sprint]').selectOption('1')
      await reopened.locator('select[name=owner]').selectOption(String(liruiming.id - 1))
      await reopened.locator('button[type=submit]').click()
      await expect(reopened).toBeHidden()
      await expect(page.locator('#toast')).toContainText('已更新 · 已同步到服务器')
      const back = (await apiGet(request, token, '/api/stories')).find(s => s.id === storyId)
      expect(back.sprint).toBe(1)
      expect(back.owner_id).toBe(liruiming.id)
      restored = true
    } finally {
      if (restored) await cleanupStory(request, token, storyId)
    }
  })

  test('[FE-BRD-05] 按负责人真名筛选后卡片数变化且全部命中该负责人', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const [stories, users] = await Promise.all([
      apiGet(request, token, '/api/stories'),
      apiGet(request, token, '/api/auth/users')
    ])
    const target = users.find(u => u.display_name === OWNER)
    const expected = stories.filter(s => s.owner_id === target.id).length
    expect(expected, `${OWNER} 名下应有故事`).toBeGreaterThan(0)
    expect(expected, '筛选后的数量应小于总数').toBeLessThan(stories.length)

    await loginAndOpen(page, '/#/board', ADMIN)
    await openBoardTab(page)
    await expect(page.locator('#board .card')).toHaveCount(stories.length)

    await page.locator('#owner').selectOption(String(target.id - 1))
    await expect(page.locator('#owner')).toHaveValue(String(target.id - 1))
    await expect(page.locator('#board .card')).toHaveCount(expected)
    const owners = await page.locator('#board .card .cardfoot .owner span:last-child').allTextContents()
    expect(owners.length).toBe(expected)
    expect([...new Set(owners.map(s => s.trim()))], '筛选结果应全部是目标负责人').toEqual([OWNER])

    // 搜索与负责人筛选叠加:命中同一张卡
    const sample = stories.find(s => s.owner_id === target.id)
    await searchBoard(page, sample.id)
    await expect(page.locator('#board .card')).toHaveCount(1)
    await expect(page.locator('#board .card .cardtop .id')).toHaveText(sample.id)

    // 复位:全部成员 + 清空搜索 → 回到全量
    await searchBoard(page, '')
    await page.locator('#owner').selectOption('all')
    await expect(page.locator('#board .card')).toHaveCount(stories.length)
  })

  test('[FE-BRD-06] 点卡打开编辑弹窗,变更记录面板展示后端审计日志', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const logs = await apiGet(request, token, '/api/stories/logs')

    await loginAndOpen(page, '/#/board', ADMIN)
    await openBoardTab(page)

    // 点卡 → 详情(编辑弹窗)预填该故事真实内容
    const dialog = await openStoryEditor(page, 'US02')
    await expect(dialog.locator('input[name=title]')).toHaveValue('角色与权限边界')
    await expect(dialog.locator('textarea[name=description]')).toHaveValue(/作为管理员，我希望配置角色和权限/)
    await expect(dialog.locator('select[name=priority]')).toHaveValue('Should')
    await expect(dialog.locator('#del')).toBeVisible()
    // 取消不产生写入
    await dialog.locator('#cancel').click()
    await expect(dialog).toBeHidden()

    // 变更记录面板:折叠态 → 展开后按后端日志条数渲染
    const panel = page.locator('details.logpanel')
    await expect(panel.locator('summary')).toContainText('变更记录')
    await panel.locator('summary').click()
    await expect(page.locator('#logbody')).toBeVisible()
    await expect(page.locator('#logbody .logrow')).toHaveCount(Math.min(logs.length, 50))
    expect(logs.length, '后端应已有审计日志').toBeGreaterThan(0)
    const typed = await page.locator('#logbody .logrow b').evaluateAll(els => els.map(e => e.className))
    expect(typed.every(c => /^t-/.test(c)), '每条日志应带类型 class').toBeTruthy()
    // 顺序(FE-D05 修复后的口径):面板**最新在前** → 首行必须是后端 desc 列表的第一条,
    // 末行是窗口内最旧的一条。旧断言取 logs[len-1] 是修复前的口径,已废弃。
    const rows = page.locator('#logbody .logrow')
    await expect(rows.first().locator('b')).toHaveText(logs[0].story_id)
    if (logs.length > 1) {
      await expect(rows.nth(1).locator('b')).toHaveText(logs[1].story_id)
      await expect(rows.last().locator('b')).toHaveText(logs[Math.min(logs.length, 50) - 1].story_id)
    }
    // 时间列(FE-D06):store 从 created_at 取 HH:MM:SS 渲染 <time>。此前后端 LogOut.createdAt
    // 漏写 @JsonProperty(项目无全局 snake_case 策略)→ 实际输出 createdAt,前端读 undefined
    // → 在线模式下时间列全空、总览「最近更新」也空白;而旧断言只查 <b> 的文本与 class,
    // 从不查 <time>,所以这条断裂长期无人发现。此处同时钉住「非空」与「与后端字段一致」。
    const firstTime = await rows.first().locator('time').textContent()
    expect(firstTime, '时间列不得为空(依赖 created_at 契约)').toMatch(/^\d{2}:\d{2}:\d{2}$/)
    expect(firstTime).toBe(String(logs[0].created_at).slice(11, 19))
    const allTimes = await page.locator('#logbody .logrow time').allTextContents()
    expect(allTimes.every(t => /^\d{2}:\d{2}:\d{2}$/.test(t)), '每条日志都应渲染出时间').toBeTruthy()

    // 面板标题随模式变化:在线模式应声明数据来自服务器审计日志
    await expect(panel.locator('summary')).toContainText('服务器审计日志')
  })

  test('[FE-BRD-07] 改故事负责人级联同步未完成子任务(确认后落库,逐字段还原)', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const users = await apiGet(request, token, '/api/auth/users')
    const liruiming = users.find(u => u.display_name === ADMIN)
    const sunqiushi = users.find(u => u.display_name === MEMBER)

    /* 夹具:US10 挂着 feature 子任务 T07(见 seed.js TASK_CARD_LINKS),两者负责人一致、任务未完成。
       后端没有 POST /api/tasks,任务无法造数,只能借用基线故事+任务对,结束时逐字段还原。 */
    const STORY = 'US10'
    const TASK = 'T07'
    const beforeStory = (await apiGet(request, token, '/api/stories')).find(s => s.id === STORY)
    const beforeTask = (await apiGet(request, token, '/api/tasks')).find(t => t.id === TASK)
    expect(beforeTask.kanban_card_id, `夹具前提:${TASK} 必须挂在 ${STORY} 上`).toBe(STORY)
    expect(beforeTask.task_type, '夹具前提:必须是 feature 子任务').toBe('feature')
    expect(beforeTask.status, `夹具前提:${TASK} 未完成才会被级联`).not.toBe(2)
    expect(beforeStory.owner_id, '夹具前提:基线上故事与子任务负责人一致').toBe(beforeTask.owner_id)

    let restored = false
    try {
      await loginAndOpen(page, '/#/board', ADMIN)
      await openBoardTab(page)
      await searchBoard(page, STORY)

      // 改负责人 → 接受同步确认 → 未完成子任务的负责人应跟着落库
      const dialog = await openStoryEditor(page, STORY)
      await dialog.locator('select[name=owner]').selectOption(String(liruiming.id - 1))
      page.once('dialog', d => d.accept())
      await dialog.locator('button[type=submit]').click()
      await expect(dialog).toBeHidden()
      await expect(page.locator('#toast')).toContainText('已更新 · 已同步到服务器')

      const storyAfter = (await apiGet(request, token, '/api/stories')).find(s => s.id === STORY)
      const taskAfter = (await apiGet(request, token, '/api/tasks')).find(t => t.id === TASK)
      expect(storyAfter.owner_id, '故事负责人应落库').toBe(liruiming.id)
      expect(taskAfter.owner_id, '未完成子任务负责人应被级联同步(一体化:故事改动跟随到执行层)').toBe(liruiming.id)

      // 刷新后改回原负责人 → 再次接受确认 → 故事与子任务逐字段还原
      await page.reload()
      await openBoardTab(page)
      await searchBoard(page, STORY)
      const reopened = await openStoryEditor(page, STORY)
      await expect(reopened.locator('select[name=owner]')).toHaveValue(String(liruiming.id - 1))
      await reopened.locator('select[name=owner]').selectOption(String(sunqiushi.id - 1))
      page.once('dialog', d => d.accept())
      await reopened.locator('button[type=submit]').click()
      await expect(reopened).toBeHidden()

      const storyBack = (await apiGet(request, token, '/api/stories')).find(s => s.id === STORY)
      const taskBack = (await apiGet(request, token, '/api/tasks')).find(t => t.id === TASK)
      expect(storyBack.owner_id, `${STORY} 负责人应还原`).toBe(beforeStory.owner_id)
      expect(taskBack.owner_id, `${TASK} 负责人应还原`).toBe(beforeTask.owner_id)
      restored = true
    } finally {
      if (!restored) {
        await apiSend(request, token, 'PATCH', `/api/stories/${STORY}`, { owner_id: beforeStory.owner_id })
        await apiSend(request, token, 'PATCH', `/api/tasks/${TASK}`, { owner_id: beforeTask.owner_id })
      }
    }
  })

  test('[FE-BRD-08] 总览密度:37 条故事一条不丢,整图由 2730px 压到一屏以内', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    await openMapWithToday(page)

    // 默认详细态:固定 148px 卡高,整图远高于一屏(这就是"看不到全局"的根因)
    await expect(page.locator('#density-detail')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('#map')).not.toHaveClass(/compact/)
    const detail = await page.evaluate(() => {
      const g = document.querySelector('#map')
      const cards = [...document.querySelectorAll('#map .card')]
      return { h: g.getBoundingClientRect().height, cardH: cards[0].getBoundingClientRect().height, n: cards.length }
    })
    expect(detail.n, '详细态卡片数 = 故事总数').toBe(stories.length)
    expect(Math.round(detail.cardH), '详细态卡高 148px').toBe(148)
    expect(detail.h, '详细态整图应远超一屏').toBeGreaterThan(2000)
    const idsBefore = await page.locator('#map .card').evaluateAll(els => els.map(e => e.dataset.id).sort())

    await page.locator('#density-compact').click()
    await expect(page.locator('#density-compact')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('#map')).toHaveClass(/compact/)
    await page.waitForTimeout(300)   // 等切换时的那次自动滚动落定
    const cs = await page.evaluate(() => {
      const g = document.querySelector('#map')
      const cards = [...document.querySelectorAll('#map .card')]
      const r = g.getBoundingClientRect()
      return {
        nh: window.innerHeight, top: Math.round(r.top), h: Math.round(r.height),
        cardH: Math.round(cards[0].getBoundingClientRect().height), n: cards.length,
        descs: document.querySelectorAll('#map .card .carddesc').length,
        statuses: document.querySelectorAll('#map .card .mapstatus').length,
        overflow: cards.filter(c => c.scrollHeight > c.clientHeight + 1 || c.scrollWidth > c.clientWidth + 1).length
      }
    })
    // 总览是降级渲染,不是过滤:id 集合必须一字不差
    expect(cs.n, '总览不得丢卡').toBe(stories.length)
    expect(await page.locator('#map .card').evaluateAll(els => els.map(e => e.dataset.id).sort())).toEqual(idsBefore)
    expect(cs.cardH, '总览小矩形 22px 高').toBe(22)
    expect(cs.h, '总览整图应压到 600px 以内').toBeLessThan(600)
    expect(cs.overflow, '小矩形内文字必须被裁掉而不是撑破卡片').toBe(0)
    // 详细态才渲染的字段在总览下不渲染
    expect(cs.descs, '总览不渲染描述').toBe(0)
    expect(cs.statuses, '总览不渲染状态/切片行').toBe(0)
    // 一屏看全:切换时会自动把地图滚进视野,滚完整图必须落在视口内
    expect(cs.top + cs.h, '总览整图必须落在视口内(切换时自动滚动)').toBeLessThanOrEqual(cs.nh)

    // 切回详细:恢复 148px 与超长整图
    await page.locator('#density-detail').click()
    await expect(page.locator('#map')).not.toHaveClass(/compact/)
    const back = await page.evaluate(() => ({
      cardH: Math.round(document.querySelector('#map .card').getBoundingClientRect().height),
      h: Math.round(document.querySelector('#map').getBoundingClientRect().height)
    }))
    expect(back.cardH).toBe(148)
    expect(back.h).toBeGreaterThan(2000)
  })

  test('[FE-BRD-09] 马甲图层:单选、图例常驻、计数守恒,且颜色之外必有字符标记', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const tasks = await apiGet(request, token, '/api/tasks')
    const users = await apiGet(request, token, '/api/auth/users')
    await openMapWithToday(page)

    // 默认马甲 = 紧急程度;图例常驻并写明规则(不写颜色名)
    await expect(page.locator('#skin')).toHaveValue('urgency')
    await expect(page.locator('#maplegend')).toBeVisible()
    await expect(page.locator('#maplegend .legendtitle')).toContainText('紧急程度')
    await expect(page.locator('#maplegend .legenditem')).toHaveCount(6)
    await expect(page.locator('#maplegend .legenditem[data-key="!"]')).toContainText('逾期')
    // 图例必须写明规则本身(阈值),而不是只给颜色名——否则周推进后无法自行复算
    await expect(page.locator('#maplegend .legenditem[data-key="!"]')).toContainText(`截止周 < W${QA_NOW_WEEK}`)
    await expect(page.locator('#maplegend .legenditem[data-key="○"]')).toContainText(`截止周 ≥ W${QA_NOW_WEEK + 3}`)

    /* 测试侧独立复算分档 → 与图例计数逐档比对(不写死 10/11/11 这类数字) */
    const counts = {}
    const glyphOf = {}
    for (const s of stories) {
      const k = urgencyKeyOf(s, tasks)
      counts[k] = (counts[k] || 0) + 1
      glyphOf[s.id] = URGENCY_GLYPH[k]
    }
    for (const [k, n] of Object.entries(counts)) {
      await expect(page.locator(`#maplegend .legenditem[data-key="${URGENCY_GLYPH[k]}"]`), `图例「${k}」计数`)
        .toHaveAttribute('data-count', String(n))
    }
    const sum = await page.locator('#maplegend .legenditem').evaluateAll(els => els.reduce((a, e) => a + Number(e.dataset.count), 0))
    expect(sum, '图例计数之和必须等于故事总数(灰色"无排期"单列但不漏)').toBe(stories.length)
    await expect(page.locator('#legendsum')).toContainText(`合计 ${stories.length} / 当前筛选 ${stories.length}`)

    /* 逐卡校验:颜色之外的第二通道(字符标记)必须与复算分档一致 */
    const marks = await page.locator('#map .card').evaluateAll(els => els.map(e => ({
      id: e.dataset.id, key: e.dataset.skinKey, sking: e.querySelector('.sking')?.textContent.trim() ?? null
    })))
    expect(marks.length).toBe(stories.length)
    for (const m of marks) {
      expect(m.key, `${m.id} 的 data-skin-key`).toBe(glyphOf[m.id])
      expect(m.sking, `${m.id} 卡内字形标记`).toBe(glyphOf[m.id])
    }
    // 单图层:每张卡恰好一个 tone-*(叠加多维度会退化成彩虹图)
    const toneCounts = await page.locator('#map .card').evaluateAll(els => els.map(e => (e.className.match(/tone-[\w-]+/g) || []).length))
    expect([...new Set(toneCounts)], '同一时刻只允许一个着色维度').toEqual([1])

    /* 总览态下同一个标记出现在小矩形里,颜色由左侧色条承担 */
    await page.locator('#density-compact').click()
    await expect(page.locator('#map')).toHaveClass(/compact/)
    const chips = await page.locator('#map .card').evaluateAll(els => els.map(e => ({
      id: e.dataset.id, mark: e.querySelector('.chipmark')?.textContent.trim() ?? null
    })))
    for (const c of chips) expect(c.mark, `${c.id} 小矩形标记`).toBe(glyphOf[c.id])
    await page.locator('#density-detail').click()

    /* 切「负责人」:图例变成成员清单,计数仍守恒 */
    await page.locator('#skin').selectOption('owner')
    await expect(page.locator('#maplegend .legendtitle')).toContainText('负责人')
    const unassigned = stories.filter(s => s.owner_id == null).length
    await expect(page.locator('#maplegend .legenditem')).toHaveCount(users.length + (unassigned ? 1 : 0))
    const ownerSum = await page.locator('#maplegend .legenditem').evaluateAll(els => els.reduce((a, e) => a + Number(e.dataset.count), 0))
    expect(ownerSum, '负责人图例计数之和仍等于故事总数').toBe(stories.length)
    const ownerGlyph = Object.fromEntries(stories.map(s => [s.id, s.owner_id == null ? '—' : 'P' + s.owner_id]))
    const ownerMarks = await page.locator('#map .card').evaluateAll(els => els.map(e => ({ id: e.dataset.id, key: e.dataset.skinKey })))
    for (const m of ownerMarks) expect(m.key, `${m.id} 的负责人标记`).toBe(ownerGlyph[m.id])

    /* 切「Sprint」:4 个切片,计数按切片归属 */
    await page.locator('#skin').selectOption('sprint')
    await expect(page.locator('#maplegend .legendtitle')).toContainText('Sprint')
    await expect(page.locator('#maplegend .legenditem')).toHaveCount(4)
    const slice = s => (s.sprint >= 4 ? 4 : s.sprint)
    for (const n of [1, 2, 3, 4]) {
      await expect(page.locator(`#maplegend .legenditem[data-key="S${n}"]`), `切片 S${n} 计数`)
        .toHaveAttribute('data-count', String(stories.filter(s => slice(s) === n).length))
    }

    /* 切「无着色」:不新增任何颜色类,卡片左边框回到基线 2px,图例整体撤走 */
    await page.locator('#skin').selectOption('none')
    await expect(page.locator('#maplegend')).toHaveCount(0)
    await expect(page.locator('#map')).not.toHaveClass(/skinned/)
    expect(await page.locator('#map .card').evaluateAll(els => els.filter(e => /tone-/.test(e.className)).length), '无着色时不得残留颜色类').toBe(0)
    expect(await page.locator('#map .card').first().evaluate(e => getComputedStyle(e).borderLeftWidth), '无着色时左边框回到基线 2px').toBe('2px')
  })

  test('[FE-BRD-10] 格内排序真实生效且不改数据;切到状态看板不受马甲影响', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const tasks = await apiGet(request, token, '/api/tasks')
    await openMapWithToday(page)

    await expect(page.locator('#sortby')).toHaveValue('origin')
    /* 空格的 id 顺序预期:按 mapcell 在 DOM 里的次序 = 切片顺序 × 活动顺序 */
    const sliceMatch = [s => s.sprint === 1, s => s.sprint === 2, s => s.sprint === 3, s => s.sprint >= 4]
    const cellIds = () => page.locator('#map .mapcell').evaluateAll(els => els.map(e => [...e.querySelectorAll('.card')].map(c => c.dataset.id)))
    const expectedCells = (cmp) => {
      const out = []
      for (let sl = 0; sl < 4; sl++) {
        for (let a = 1; a <= 5; a++) {
          const list = stories.filter(s => sliceMatch[sl](s) && s.activity === a).map(s => s.id)
          out.push(cmp ? cmp(list) : list)
        }
      }
      return out
    }
    expect(await cellIds(), '原次序 = 数据自身次序,不得重排').toEqual(expectedCells())

    const byOwner = list => [...list].sort((x, y) => {
      const o = id => { const s = stories.find(v => v.id === id); return s.owner_id == null ? 999 : s.owner_id }
      return o(x) - o(y)
    })
    await page.locator('#sortby').selectOption('owner')
    await expect(page.locator('#sortby')).toHaveValue('owner')
    expect(await cellIds(), '按负责人排序:格内按 owner_id 升序,未分配排最后').toEqual(expectedCells(byOwner))

    const rank = id => {
      const s = stories.find(v => v.id === id)
      if (s.status === 2) return 999
      const d = deadlineWeekOf(s, tasks)
      return d == null ? 998 : d - QA_NOW_WEEK
    }
    const byUrgency = list => [...list].sort((x, y) => rank(x) - rank(y))
    await page.locator('#sortby').selectOption('urgency')
    expect(await cellIds(), '按紧急程度排序:格内按「截止周 − 当前周」升序').toEqual(expectedCells(byUrgency))

    // 排序只影响呈现:接口数据不变
    const after = await apiGet(request, token, '/api/stories')
    expect(after.map(s => s.id)).toEqual(stories.map(s => s.id))

    /* 状态看板:不带马甲、不带总览,卡高保持基线 132px,地图控件收起 */
    await page.locator('#density-compact').click()
    await page.locator('#boardtab').click()
    await expect(page.locator('#boardpanel')).toBeVisible()
    await expect(page.locator('#density-compact')).toHaveCount(0)
    await expect(page.locator('#skin')).toHaveCount(0)
    const board = await page.evaluate(() => {
      const cards = [...document.querySelectorAll('#board .card')]
      return {
        n: cards.length, h: Math.round(cards[0].getBoundingClientRect().height),
        toned: cards.filter(c => /tone-/.test(c.className)).length,
        chipped: cards.filter(c => /\bchip\b/.test(c.className)).length
      }
    })
    expect(board.n, '状态看板卡片数不受影响').toBe(stories.length)
    expect(board.h, '状态看板卡高保持基线 132px').toBe(132)
    expect(board.toned, '状态看板不套马甲(已按状态分列,再上色是冗余)').toBe(0)
    expect(board.chipped, '状态看板不进入总览').toBe(0)

    // 切回地图:密度选择被保留
    await page.locator('#maptab').click()
    await expect(page.locator('#map')).toHaveClass(/compact/)
  })

  test('[FE-BRD-11] 同一份数据换「今天」→ 紧急度分档随之推进,证明该图层不是静态装饰', async ({ browser, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const tasks = await apiGet(request, token, '/api/tasks')
    const rows = {}

    for (const [day, week] of [['2026-08-31', 1], ['2026-10-01', 5]]) {
      const ctx = await browser.newContext()
      const page = await ctx.newPage()
      await page.addInitScript(d => { window.__AICAP_TODAY__ = d }, day)
      await loginAndOpen(page, '/#/board', ADMIN)
      await expect(page.locator('#map .card').first()).toBeVisible()
      await expect(page.locator('#todayanchor')).toContainText(`第 ${week} 周`)
      const overdue = stories.filter(s => urgencyKeyOf(s, tasks, week) === 'overdue').length
      rows[day] = {
        week, overdue,
        ui: Number(await page.locator('#maplegend .legenditem[data-key="!"]').getAttribute('data-count')),
        sum: await page.locator('#maplegend .legenditem').evaluateAll(els => els.reduce((a, e) => a + Number(e.dataset.count), 0))
      }
      expect(rows[day].ui, `${day}(W${week}) 界面逾期数 = 复算值`).toBe(overdue)
      expect(rows[day].sum, `${day}(W${week}) 图例计数仍守恒`).toBe(stories.length)
      await ctx.close()
    }
    expect(rows['2026-10-01'].overdue, 'W5 的逾期数必须严格多于 W1(否则说明锚点没被读取)')
      .toBeGreaterThan(rows['2026-08-31'].overdue)
  })
})

/* ==================================================================================
 * FE-GNT · 甘特图
 * ================================================================================== */
test.describe('FE-GNT 甘特图', () => {
  test('[FE-GNT-01] 父卡(故事)行与子任务行都存在且带 data-gantt-task', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const tasks = await apiGet(request, token, '/api/tasks')
    const feature = tasks.filter(t => (t.task_type || 'feature') === 'feature' && t.kanban_card_id)
    const mgmt = tasks.filter(t => t.task_type === 'management')
    const cards = [...new Set(feature.map(t => t.kanban_card_id))]

    await loginAndOpen(page, '/#/gantt', ADMIN)

    await expect(page.locator('.grow.parent')).toHaveCount(cards.length)
    await expect(page.locator('.grow.mgmt')).toHaveCount(mgmt.length)
    await expect(page.locator('.grow.parent + .grow.child')).toHaveCount(cards.length)
    await expect(page.locator('[data-gantt-task^="story:"]')).toHaveCount(cards.length)
    await expect(page.locator('[data-gantt-task^="task:"]')).toHaveCount(tasks.length)

    // 父条文案 = 「USxx · 加权%」,子条标签 = T 编号
    const parentLabels = await page.locator('.gbar.parent').allTextContents()
    expect(parentLabels.length).toBe(cards.length)
    for (const label of parentLabels) expect(label.trim()).toMatch(/^US\d+ · \d+%$/)
    const childLabels = await page.locator('.grow.child .gbar').allTextContents()
    expect(childLabels.every(l => /^T\d+$/.test(l.trim())), '子任务条标签应为 T 编号').toBeTruthy()

    // 父行的血缘说明文案与卡上子任务数一致
    const parentText = await page.locator('.grow.parent .tname small').first().textContent()
    expect(parentText).toMatch(/^父卡 · \d+ 子任务 · \d+h · 加权 \d+%$/)
    // 管理任务行带管理标记,且不挂父卡
    await expect(page.locator('#gantt-legend')).toContainText('管理任务')
    await expect(page.locator('#gantt-legend')).toContainText('父卡条 = 子任务周并集')
  })

  test('[FE-GNT-02] 点击任务条出现详情面板:负责人/排期/前置后续齐全且高亮正确', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const tasks = await apiGet(request, token, '/api/tasks')
    const users = await apiGet(request, token, '/api/auth/users')
    const t03 = tasks.find(t => t.id === 'T03')
    const t03Owner = users.find(u => u.id === t03.owner_id).display_name
    const deps = tasks.filter(t => (t.depends_on || '').split(',').map(s => s.trim()).includes('T03'))

    await loginAndOpen(page, '/#/gantt', ADMIN)
    await expect(page.locator('#gantt-detail')).toContainText('点击任务条')

    await page.locator('[data-gantt-task="task:T03"]').click()
    const detail = page.locator('#gantt-detail')
    await expect(detail).toContainText(t03.name)
    await expect(detail).toContainText(t03Owner)
    await expect(detail).toContainText(`${t03.hours}h`)
    await expect(detail).toContainText(`W${t03.week_start}–W${t03.week_end}`)
    await expect(detail).toContainText(t03.kanban_card_id)
    await expect(detail.locator('.detail-grid')).toContainText('前置任务')
    await expect(detail.locator('.detail-grid')).toContainText('后续任务')
    await expect(detail).toContainText(t03.depends_on)
    for (const d of deps) await expect(detail).toContainText(d.id)

    // 前置/后续任务条高亮:数量与依赖数据一致
    await expect(page.locator('.gbar.predecessor')).toHaveCount(t03.depends_on.split(',').filter(Boolean).length)
    await expect(page.locator('.gbar.dependent')).toHaveCount(deps.length)
    await expect(page.locator('[data-gantt-task="task:T03"]')).toHaveClass(/selected/)
  })

  test('[FE-GNT-03] 详情面板「编辑任务」改工时真实往返(8h → 9h → 8h 并核对落库)', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const before = (await apiGet(request, token, '/api/tasks')).find(t => t.id === 'T16')
    expect(before.task_type, 'T16 应为管理任务').toBe('management')

    try {
      await loginAndOpen(page, '/#/gantt', ADMIN)
      await page.locator('[data-gantt-task="task:T16"]').click()
      const detail = page.locator('#gantt-detail')
      await expect(detail).toContainText('T16')
      await expect(detail).toContainText(`${before.hours}h`)

      await detail.locator('button').filter({ hasText: '编辑任务' }).click()
      const dialog = page.locator('#task-editor')
      await expect(dialog).toBeVisible()
      await expect(dialog.locator('#task-editid')).toContainText('T16')
      await expect(dialog.locator('input[name=hours]')).toHaveValue(String(before.hours))
      await expect(dialog.locator('select[name=weekStart]')).toHaveValue(String(before.week_start))
      await expect(dialog.locator('select[name=weekEnd]')).toHaveValue(String(before.week_end))
      await expect(dialog.locator('input[name=storyRef]')).toHaveValue(before.story_ref)

      await dialog.locator('input[name=hours]').fill(String(before.hours + 1))
      await dialog.locator('button[type=submit]').click()
      await expect(dialog).toBeHidden()
      await expect(page.locator('#toast')).toContainText('任务已更新 · 已同步到服务器')
      await expect(detail).toContainText(`${before.hours + 1}h`)

      const changed = (await apiGet(request, token, '/api/tasks')).find(t => t.id === 'T16')
      expect(changed.hours, '改后的工时必须真实落库').toBe(before.hours + 1)

      // 校验 UI 与后端同口径:开始周晚于结束周被拒绝,不落库
      await detail.locator('button').filter({ hasText: '编辑任务' }).click()
      await expect(dialog).toBeVisible()
      await dialog.locator('select[name=weekStart]').selectOption('6')
      await dialog.locator('select[name=weekEnd]').selectOption('2')
      await dialog.locator('button[type=submit]').click()
      await expect(page.locator('#toast')).toContainText('开始周不能晚于结束周')
      await expect(dialog).toBeVisible()
      expect((await apiGet(request, token, '/api/tasks')).find(t => t.id === 'T16').week_start).toBe(before.week_start)

      // 改回原值 + 相邻周序(6 > 2)一并复原
      await dialog.locator('input[name=hours]').fill(String(before.hours))
      await dialog.locator('select[name=weekStart]').selectOption(String(before.week_start))
      await dialog.locator('select[name=weekEnd]').selectOption(String(before.week_end))
      await dialog.locator('button[type=submit]').click()
      await expect(dialog).toBeHidden()
      await expect(detail).toContainText(`${before.hours}h`)
      const restored = (await apiGet(request, token, '/api/tasks')).find(t => t.id === 'T16')
      expect(restored.hours).toBe(before.hours)
      expect(restored.week_start).toBe(before.week_start)
      expect(restored.week_end).toBe(before.week_end)
      expect(restored.story_ref).toBe(before.story_ref)
      expect(restored.owner_id).toBe(before.owner_id)
      expect(restored.name).toBe(before.name)
    } finally {
      // 兜底还原:任何一步失败都把 T16 拉回用例开始时的状态
      const now = (await apiGet(request, token, '/api/tasks')).find(t => t.id === 'T16')
      if (now.hours !== before.hours || now.week_start !== before.week_start || now.week_end !== before.week_end) {
        await apiSend(request, token, 'PATCH', '/api/tasks/T16', {
          name: before.name, owner_id: before.owner_id, hours: before.hours,
          week_start: before.week_start, week_end: before.week_end,
          story_ref: before.story_ref, depends_on: before.depends_on || ''
        })
      }
    }
  })

  test('[FE-GNT-04] 点击父卡(story)条出现故事详情,并有编辑故事与查看父卡入口', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const tasks = await apiGet(request, token, '/api/tasks')
    const subs = featureSubs(tasks, 'US01')

    await loginAndOpen(page, '/#/gantt', ADMIN)

    // 父卡条 → 故事详情(子任务血缘 + 编辑故事)
    await page.locator('[data-gantt-task="story:US01"]').click()
    const detail = page.locator('#gantt-detail')
    await expect(detail).toContainText('US01')
    await expect(detail).toContainText('项目与成员范围')
    await expect(detail).toContainText('工时加权进度')
    await expect(detail.locator('.detail-sub')).toContainText(`子任务（${subs.length}）`)
    await expect(detail.locator('.task-chip')).toHaveCount(subs.length)
    await expect(detail.locator('button').filter({ hasText: '编辑故事' })).toBeVisible()

    // 子任务条 → 任务详情里的「查看父卡 US01」可切回故事详情
    await page.locator('[data-gantt-task="task:T03"]').click()
    const backToParent = detail.locator('button').filter({ hasText: '查看父卡 US01' })
    await expect(backToParent).toBeVisible()
    await backToParent.click()
    await expect(detail).toContainText('子任务（' + subs.length + '）')
    await expect(detail.locator('button').filter({ hasText: '编辑故事' })).toBeVisible()

    // 编辑故事入口能打开弹窗且内容指向 US01(只读检查,取消不写入)
    await detail.locator('button').filter({ hasText: '编辑故事' }).click()
    const dialog = page.locator('#editor')
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('#editid')).toContainText('US01 · 修改会同步到看板与故事地图')
    await expect(dialog.locator('input[name=title]')).toHaveValue('项目与成员范围')
    await dialog.locator('#cancel').click()
    await expect(dialog).toBeHidden()

    // 子任务 chip 可下钻到对应任务详情(此时仍处于父卡选中态)
    await expect(detail.locator('.task-chip')).toHaveCount(subs.length)
    await detail.locator('.task-chip').first().click()
    await expect(detail.locator('.detail-head .id')).toHaveText(subs[0].id)
    await expect(detail).toContainText('前置任务')
    await expect(detail).toContainText('后续任务')
  })

  test('[FE-GNT-05] 选中态互斥:点另一条后前一条取消选中,再点同一条清空', async ({ page }) => {
    await loginAndOpen(page, '/#/gantt', ADMIN)
    const detail = page.locator('#gantt-detail')

    await page.locator('[data-gantt-task="task:T03"]').click()
    await expect(page.locator('.gbar.selected')).toHaveCount(1)
    await expect(page.locator('[data-gantt-task="task:T03"]')).toHaveClass(/selected/)
    await expect(page.locator('.grow.selected')).toHaveCount(1)
    await expect(detail.locator('.detail-head .id')).toHaveText('T03')

    await page.locator('[data-gantt-task="task:T07"]').click()
    await expect(page.locator('.gbar.selected')).toHaveCount(1)
    await expect(page.locator('[data-gantt-task="task:T07"]')).toHaveClass(/selected/)
    await expect(page.locator('[data-gantt-task="task:T03"]')).not.toHaveClass(/selected/)
    await expect(page.locator('.grow.selected')).toHaveCount(1)
    await expect(detail.locator('.detail-head .id')).toHaveText('T07')

    // 父卡与子任务之间也互斥
    await page.locator('[data-gantt-task="story:US01"]').click()
    await expect(page.locator('.gbar.selected')).toHaveCount(1)
    await expect(page.locator('[data-gantt-task="story:US01"]')).toHaveClass(/selected/)
    await expect(page.locator('[data-gantt-task="task:T07"]')).not.toHaveClass(/selected/)

    // 再点同一条 = 取消选中,详情面板回到空态
    await page.locator('[data-gantt-task="story:US01"]').click()
    await expect(page.locator('.gbar.selected')).toHaveCount(0)
    await expect(page.locator('.grow.selected')).toHaveCount(0)
    await expect(detail).toContainText('点击任务条')
  })
})

/* ==================================================================================
 * FE-MBR · 成员任务图
 * ================================================================================== */
test.describe('FE-MBR 成员任务图', () => {
  test('[FE-MBR-01] 5 张成员卡显示真实姓名/容量/负载档位,负载图例 4 档齐全', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const [users, tasks] = await Promise.all([
      apiGet(request, token, '/api/auth/users'),
      apiGet(request, token, '/api/tasks')
    ])
    const expectedNames = users.map(u => u.display_name)
    const allocated = id => tasks.filter(t => t.owner_id === id).reduce((a, t) => a + Math.max(0, Number(t.hours) || 0), 0)
    const loadLabel = id => {
      const u = users.find(x => x.id === id)
      const util = u.capacity_hours ? allocated(id) / u.capacity_hours : 0
      if (util > 1) return '超载'
      if (util >= 0.8) return '偏忙'
      if (util >= 0.6) return '正常'
      return '充足'
    }

    await loginAndOpen(page, '/#/members', ADMIN)

    const cards = page.locator('#member-grid .mcard')
    await expect(cards).toHaveCount(expectedNames.length)
    expect((await cards.locator('h3').allTextContents()).map(s => s.trim())).toEqual(expectedNames)

    for (const u of users) {
      const card = page.locator(`#member-grid .mcard[data-member="${u.id - 1}"]`)
      await expect(card.locator('.loadflag')).toHaveText(loadLabel(u.id))
      await expect(card.locator('.statline')).toContainText(`工时 ${allocated(u.id)}h`)
    }

    // 负载图例 4 档 + 口径说明
    const legend = page.locator('.load-legend span')
    await expect(legend).toHaveCount(4)
    expect((await legend.allTextContents()).map(s => s.trim())).toEqual(['低负载', '正常', '偏忙', '超载'])
    await expect(page.locator('.load-note')).toContainText('计划工时')
    await expect(page.locator('.load-note')).toContainText('周容量')

    // 容量与分配:Bandwidth 5 张卡,容量取 users.capacity_hours
    const bw = page.locator('#bandwidth .mcard')
    await expect(bw).toHaveCount(users.length)
    for (const u of users) {
      const card = bw.filter({ has: page.locator('h3', { hasText: u.display_name }) })
      await expect(card.locator('.role')).toContainText(`容量 ${u.capacity_hours}h`)
      await expect(card.locator('.role')).toContainText(`已分配 ${allocated(u.id)}h`)
    }

    // 热力图:每人 6 周格 + 周容量写入 tooltip
    await expect(page.locator('#heat .heat-row')).toHaveCount(users.length)
    await expect(page.locator('#heat .heat-cell')).toHaveCount(users.length * 6)
    await expect(page.locator('#heat .heat-cell').first()).toHaveAttribute('title', /周容量/)
  })

  test('[FE-MBR-02] 点击成员卡打开任务抽屉,任务列表与统计与后端一致', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const [users, tasks] = await Promise.all([
      apiGet(request, token, '/api/auth/users'),
      apiGet(request, token, '/api/tasks')
    ])
    const target = users.find(u => u.display_name === ADMIN)
    const mine = tasks.filter(t => t.owner_id === target.id)
    const alloc = mine.reduce((a, t) => a + Math.max(0, Number(t.hours) || 0), 0)
    const doing = mine.filter(t => taskStatusKey(t) === 'doing')
    const done = mine.filter(t => taskStatusKey(t) === 'done')
    const unfinished = mine.filter(t => taskStatusKey(t) !== 'done')

    await loginAndOpen(page, '/#/members', ADMIN)
    await page.locator(`#member-grid .mcard[data-member="${target.id - 1}"]`).click()

    const drawer = page.locator('#member-drawer')
    await expect(drawer).toBeVisible()
    await expect(page.locator('#drawer-title')).toHaveText(`P${target.id} ${target.display_name} · 任务明细`)
    await expect(page.locator('#member-detail')).toContainText(`总任务 ${mine.length}`)
    await expect(page.locator('#member-detail')).toContainText(`未完成 ${unfinished.length}`)
    await expect(page.locator('#member-detail')).toContainText(`容量 ${target.capacity_hours}h`)
    await expect(page.locator('.member-summary')).toContainText(`可分配工时`)
    await expect(page.locator('.member-summary')).toContainText(`${alloc}h / ${target.capacity_hours}h`)

    const rows = drawer.locator('.task-item')
    await expect(rows).toHaveCount(mine.length)
    expect((await rows.locator('.task-key').allTextContents()).map(s => s.trim()).sort())
      .toEqual(mine.map(t => t.id).sort())
    for (const t of mine) {
      const row = rows.filter({ has: page.locator(`.task-key:text-is("${t.id}")`) })
      await expect(row.locator('.task-meta')).toContainText(`W${t.week_start}–W${t.week_end}`)
      await expect(row.locator('.task-meta')).toContainText(`${t.hours}h`)
    }

    // 状态筛选:进行中
    await drawer.locator('[data-member-filter="doing"]').click()
    await expect(rows).toHaveCount(doing.length)
    if (doing.length) {
      expect((await rows.locator('.task-key').allTextContents()).map(s => s.trim()).sort())
        .toEqual(doing.map(t => t.id).sort())
    } else {
      await expect(drawer.locator('.task-list .empty')).toHaveText('该筛选条件下暂无任务。')
    }
    // 已完成筛选(基线无已完成任务时应显示空态)
    await drawer.locator('[data-member-filter="done"]').click()
    await expect(rows).toHaveCount(done.length)
    // 回到全部
    await drawer.locator('[data-member-filter="all"]').click()
    await expect(rows).toHaveCount(mine.length)
  })

  test('[FE-MBR-03] 抽屉可用 Esc、关闭按钮与遮罩三种方式关闭', async ({ page }) => {
    await loginAndOpen(page, '/#/members', ADMIN)
    const drawer = page.locator('#member-drawer')

    // Esc
    await page.locator('#member-grid .mcard[data-member="1"]').click()
    await expect(drawer).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()

    // 关闭按钮
    await page.locator('#member-grid .mcard[data-member="1"]').click()
    await expect(drawer).toBeVisible()
    await drawer.locator('button.drawer-close[data-drawer-close]').click()
    await expect(drawer).toBeHidden()

    // 遮罩:点抽屉左半屏的空白处(右半屏是 .drawer-panel)
    await page.locator('#member-grid .mcard[data-member="1"]').click()
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('.drawer-backdrop[data-drawer-close]')).toBeAttached()
    await page.mouse.click(400, 300)
    await expect(drawer).toBeHidden()
    // 关闭后卡片选中态同步取消
    await expect(page.locator('#member-grid .mcard.selected')).toHaveCount(0)
  })

  test('[FE-MBR-04] 成员画像板块存在,编辑弹窗改一项保存后能改回原值', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const before = await apiGet(request, token, '/api/members/profiles')
    const target = before.find(p => p.display_name === ADMIN)
    const snapshot = JSON.parse(JSON.stringify(target))
    const restore = async () => {
      await apiSend(request, token, 'PATCH', `/api/members/${snapshot.user_id}/profile`, {
        title: snapshot.title,
        summary: snapshot.summary,
        years_experience: snapshot.years_experience,
        tech_stack: snapshot.tech_stack,
        capabilities: snapshot.capabilities,
        process_domains: snapshot.process_domains
      })
      const after = await apiGet(request, token, '/api/members/profiles')
      const now = after.find(p => p.user_id === snapshot.user_id)
      const strip = ({ updated_at, ...rest }) => rest
      expect(strip(now), '画像必须逐字段还原').toEqual(strip(snapshot))
    }

    try {
      await loginAndOpen(page, '/#/members', ADMIN)

      // 画像板块(顶部)渲染 5 张卡,第一名管理员带「编辑画像」
      const cards = page.locator('#member-profiles .pcard')
      await expect(cards).toHaveCount(before.length)
      expect((await cards.locator('.phead b').allTextContents()).map(s => s.trim()))
        .toEqual(before.map(p => p.display_name))
      const myCard = cards.filter({ has: page.locator('.phead b', { hasText: ADMIN }) })
      await expect(myCard.locator('.pdim')).toHaveCount(3)
      await expect(myCard.locator('.pdim-t').first()).toHaveText('技术栈')
      expect(await myCard.locator('.chip').count(), '画像应有技能条目').toBeGreaterThan(0)

      await myCard.locator('button.edit').click()
      const dialog = page.locator('#profile-dialog')
      await expect(dialog).toBeVisible()
      await expect(dialog.locator('.dhead h3')).toHaveText(`${ADMIN} · 成员画像`)
      await expect(dialog.locator('input[type=number]')).toHaveValue(String(snapshot.years_experience))

      // 改一项(经验年限 +1)并保存
      await dialog.locator('input[type=number]').fill(String(snapshot.years_experience + 1))
      await dialog.locator('#profile-save').click()
      await expect(dialog).toBeHidden()
      // 弹窗内先提示「已保存 X 的画像」,随后父组件的 onSaved 覆盖为「画像已更新」
      await expect(page.locator('#toast')).toContainText('画像已更新')
      const changed = (await apiGet(request, token, '/api/members/profiles')).find(p => p.user_id === snapshot.user_id)
      expect(changed.years_experience).toBe((snapshot.years_experience || 0) + 1)

      // 界面同步更新
      await expect(myCard.locator('.phead .small')).toContainText(`${snapshot.years_experience + 1} 年经验`)

      // 改回原值并逐字段核对
      await myCard.locator('button.edit').click()
      await expect(dialog).toBeVisible()
      await dialog.locator('input[type=number]').fill(String(snapshot.years_experience))
      await dialog.locator('#profile-save').click()
      await expect(dialog).toBeHidden()
      await expect(myCard.locator('.phead .small')).toContainText(`${snapshot.years_experience} 年经验`)
    } finally {
      await restore()
    }
  })
})

/* ==================================================================================
 * FE-UML · UML 图
 * ================================================================================== */
test.describe('FE-UML UML 图', () => {
  test('[FE-UML-01] 用例图 SVG 渲染 4 参与者与 15 用例节点(用例名取自真实故事标题)', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const us01 = stories.find(s => s.id === 'US01')

    await loginAndOpen(page, '/#/uml', ADMIN)

    const svg = page.locator('.usecase-svg')
    await expect(svg).toBeVisible()
    await expect(svg.locator('.uc-hit')).toHaveCount(15)
    await expect(svg.locator('.actor-hit')).toHaveCount(4)
    await expect(svg.locator('.association')).toHaveCount(27)          // 23 条角色关联 + 4 条图例线
    await expect(svg.locator('.usecase')).toHaveCount(15)
    await expect(svg.locator('.system-title')).toHaveText('爱管理系统')

    // 4 个角色名与图例
    const actorNames = (await svg.locator('.actor-name').allTextContents()).map(s => s.trim())
    expect(actorNames).toEqual(['管理员', '项目负责人 / DRI', '团队成员', '普通用户 / 数据查看者'])

    // 用例名/编号来自真实故事基线(US01 的标题出现在图上)
    const svgText = await svg.textContent()
    expect(svgText).toContain('US01')
    expect(svgText).toContain(us01.title)
    // 说明文案里的故事条数与后端一致
    await expect(page.locator('.uml-note')).toContainText(`${stories.length} 条故事`)
  })

  test('[FE-UML-02] 点击用例节点展开故事明细面板,可关闭', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const us01 = stories.find(s => s.id === 'US01')

    await loginAndOpen(page, '/#/uml', ADMIN)
    const detail = page.locator('.uc-detail')
    await expect(detail).toHaveCount(0)

    // 第一个用例节点 = US01(登录/退出 区)
    await page.locator('.usecase-svg .uc-hit').first().click()
    await expect(detail).toBeVisible()
    await expect(detail).toContainText('US01')
    await expect(detail).toContainText(us01.title)
    await expect(detail.locator('.uc-story .mono')).toHaveText('US01')
    await expect(detail.locator('.uc-detail-head .small')).toContainText('关联 1 条故事')
    // 鼠标移开后:选中态标记落在唯一一个用例节点上
    await page.mouse.move(0, 0)
    await expect(page.locator('.usecase-svg .uc-hit.is-sel')).toHaveCount(1)
    await expect(page.locator('.usecase-svg .uc-hit.is-sel')).toHaveAttribute('aria-pressed', 'true')

    await detail.locator('.uc-detail-close').click()
    await expect(detail).toHaveCount(0)

    // 多故事用例(US02/US08/US16)展开 3 条明细
    await page.locator('.usecase-svg .uc-hit').nth(2).click()
    await expect(detail).toBeVisible()
    await expect(detail.locator('.uc-story')).toHaveCount(3)
    await expect(detail.locator('.uc-detail-head .mono')).toContainText('US02')
    await detail.locator('.uc-detail-close').click()
    await expect(detail).toHaveCount(0)
  })

  test('[FE-UML-03] 时序图参与者为 Spring Boot 后端,不含 FastAPI 字样', async ({ page }) => {
    await loginAndOpen(page, '/#/uml', ADMIN)

    const seq = page.locator('.sequence-svg')
    await expect(seq).toBeVisible()
    const text = await seq.textContent()
    expect(text, '时序图后端参与者应为当前 Java 后端').toContain('Spring Boot 后端')
    expect(text, '不应残留 FastAPI 字样').not.toContain('FastAPI')
    expect(text).toContain('爱管理前端')
    expect(text).toContain('MySQL 数据库')
    expect(text).toContain('登录并加载项目工作台')

    await expect(seq.locator('.participant')).toHaveCount(5)
    await expect(seq.locator('[data-seq-message]')).toHaveCount(14)
    // 消息明细引用当前后端真实路由
    expect(text).toContain('/api/auth/login')
    expect(text).toContain('/api/stories')
    expect(text).toContain('US01-US37 共 37 条故事基线')
  })

  test('[FE-UML-04] 用例标注由故事数据派生:无幽灵编号,新增故事立即反映到「未映射」', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const stories = await apiGet(request, token, '/api/stories')
    const storyIds = new Set(stories.map(s => s.id))

    await loginAndOpen(page, '/#/uml', ADMIN)

    /* ① 节点上的 US 标注必须是**派生**的:每个编号都要真实存在。
       此前 ref 是手写常量(如 'US02 / US08 / US16'),删掉故事后它会变成幽灵编号继续挂在椭圆上。 */
    const refs = await page.locator('.usecase-svg .story-ref').allTextContents()
    expect(refs.length, '15 个用例都应带 US 标注').toBe(15)
    const labelled = new Set()
    for (const raw of refs) {
      for (const part of String(raw).split('/')) {
        const range = /^(US\d+)\s*-\s*(US\d+)$/.exec(part.trim())
        if (range) {
          const a = Number(range[1].slice(2)), b = Number(range[2].slice(2))
          for (let n = a; n <= b; n++) labelled.add('US' + String(n).padStart(2, '0'))
        } else if (/^US\d+$/.test(part.trim())) {
          labelled.add(part.trim())
        }
      }
    }
    expect(labelled.size, '应能从标注中解析出故事编号').toBeGreaterThan(0)
    expect([...labelled].filter(id => !storyIds.has(id)),
      '节点标注不得出现基线中不存在的故事编号(幽灵编号)').toEqual([])

    /* ② 自洽:被标注的故事 ∪ 未映射的故事 == 全部故事(既不重复也不漏计) */
    const readUnmapped = async () => {
      await expect(page.locator('#uml-unmapped')).toBeVisible()
      const t = await page.locator('#uml-unmapped').textContent()
      return Number(/故事\s*(\d+)\s*条/.exec(t)[1])
    }
    const before = await readUnmapped()
    expect(labelled.size + before, '标注数 + 未映射数 必须等于故事总数').toBe(stories.length)

    /* ③ 跟随变化:新建一个故事后,UML 视图必须立刻反映(未映射 +1 且列出该编号)。
       这是「用例图跟随故事变化」的可验证口径 —— 布局/归属是需求设计,但「有故事没进用例图」必须可见。 */
    const title = uniq('QA-UML-UNMAPPED')
    const created = await apiSend(request, token, 'POST', '/api/stories', {
      title,
      description: '作为测试工程师，我希望新建故事后 UML 视图立刻反映未映射编号，以便验证用例图跟随数据变化',
      acceptance: '未映射计数 +1 且提示文本包含该编号；用例结束前删除',
      priority: 'Could', sprint: 1, activity: 2, status: 0, owner_id: 1
    })
    expect(created.status, '造数:创建故事应成功').toBeLessThan(300)
    const newId = created.body.id
    let cleaned = false
    try {
      await page.reload()
      await expect(page.locator('.usecase-svg')).toBeVisible()
      expect(await readUnmapped(), '新增故事应使未映射计数 +1').toBe(before + 1)
      await expect(page.locator('#uml-unmapped')).toContainText(newId)
    } finally {
      await cleanupStory(request, token, newId)
      cleaned = true
    }
    expect(cleaned, '新增的故事必须被清理').toBeTruthy()
    // 清理后回到基线口径
    await page.reload()
    await expect(page.locator('.usecase-svg')).toBeVisible()
    expect(await readUnmapped(), '清理后未映射计数应回到基线').toBe(before)
  })
})

/* ==================================================================================
 * FE-AI · AI 助手(会议智能体 / 会议音频)
 * ================================================================================== */
test.describe('FE-AI AI 助手', () => {
  test('[FE-AI-01] 会议智能体:创建会议 → 落库 → 选中 → 删除(用例自清理)', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    // FE-D03 修复后后端提供 DELETE /api/meetings/{id}(admin/owner),本用例恢复"造数即清理"
    const title = uniq('QA-AI-MEETING')
    const transcript = `本次 QA 回归会议 ${title}:确认看板与甘特血缘口径一致,成员负载按周容量计算。`

    await loginAndOpen(page, '/#/ai', ADMIN)
    const form = page.locator('#meeting-save-form')
    await expect(form).toBeVisible()
    await form.locator('input[name=title]').fill(title)
    await form.locator('textarea[name=transcript]').fill(transcript)
    await form.locator('button[type=submit]').click()
    await expect(page.locator('#toast')).toContainText('会议已保存到服务器')

    // 落库校验
    const meetings = await apiGet(request, token, '/api/meetings')
    const mine = meetings.find(m => m.title === title)
    expect(mine, '会议应已落库').toBeTruthy()
    expect(mine.transcript).toBe(transcript)

    // 界面:出现在会议下拉里,选择后被自动选中(转写区展示该会议的转写)
    await page.locator('#meeting-select').selectOption(mine.id)
    await expect(page.locator('#meeting-select option').filter({ hasText: title })).toHaveCount(1)
    await expect(page.locator('#meeting-select')).toHaveValue(mine.id)
    await expect(page.locator('#saved-transcript')).toContainText(transcript)
    // 表单已恢复为空,便于重复执行
    await expect(form.locator('input[name=title]')).toHaveValue('')
    await expect(form.locator('textarea[name=transcript]')).toHaveValue('')

    // 删除自清理:走界面入口(含 confirm 二次确认)
    page.once('dialog', d => d.accept())
    await expect(page.locator('#meeting-delete')).toBeEnabled()
    await page.locator('#meeting-delete').click()
    await expect(page.locator('#toast')).toContainText('会议已删除')

    // 后端副作用:该会议彻底消失(再删一次应 404),列表与建议队列都清空
    const again = await request.delete(`${API}/api/meetings/${mine.id}`, { headers: authHeaders(token) })
    expect(again.status(), '删除后该会议应已不存在(404)').toBe(404)
    const after = await apiGet(request, token, '/api/meetings')
    expect(after.find(m => m.id === mine.id), '会议应从列表移除').toBeFalsy()
    const sugg = await apiGet(request, token, `/api/suggestions?meetingId=${mine.id}`)
    expect(sugg.length, '该会议的建议记录应一并清理').toBe(0)

    // 界面复位:下拉里不再有它,转写区回到空态
    await expect(page.locator('#meeting-select option').filter({ hasText: title })).toHaveCount(0)
    await expect(page.locator('#saved-transcript')).toContainText('暂无会议')
  })

  test('[FE-AI-04] 会议删除入口权限(admin):按钮可用且提示确认后果', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const meetings = await apiGet(request, token, '/api/meetings')
    expect(meetings.length, '需要至少一场既存会议用于观察按钮状态').toBeGreaterThan(0)

    await loginAndOpen(page, '/#/ai', ADMIN)
    await page.locator('#meeting-select').selectOption(meetings[0].id)
    await expect(page.locator('#meeting-delete')).toBeEnabled()
    // 不真删:点击后取消确认框,按钮与列表都不应变
    page.once('dialog', d => d.dismiss())
    await page.locator('#meeting-delete').click()
    const still = await apiGet(request, token, '/api/meetings')
    expect(still.find(m => m.id === meetings[0].id), '取消确认后会议必须仍然存在').toBeTruthy()
  })

  test('[FE-AI-05] 会议删除入口权限(member):入口禁用并给出角色原因', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const meetings = await apiGet(request, token, '/api/meetings')
    expect(meetings.length).toBeGreaterThan(0)

    await loginAndOpen(page, '/#/ai', MEMBER)
    await page.locator('#meeting-select').selectOption(meetings[0].id)
    await expect(page.locator('#meeting-delete')).toBeDisabled()
    await expect(page.locator('#meeting-delete')).toHaveAttribute('title', /管理员或负责人/)
  })

  test('[FE-AI-06] 会议删除入口权限(viewer):入口禁用且标记只读', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const meetings = await apiGet(request, token, '/api/meetings')
    expect(meetings.length).toBeGreaterThan(0)

    await loginAndOpen(page, '/#/ai', VIEWER)
    await page.locator('#meeting-select').selectOption(meetings[0].id)
    await expect(page.locator('#meeting-delete')).toBeDisabled()
    await expect(page.locator('#meeting-delete')).toHaveAttribute('title', /只读/)
  })

  test('[FE-AI-02] agent/config 拉取后界面显示「已配置模型」状态,分析按钮可用', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const cfg = await apiGet(request, token, '/api/agent/config')
    expect(cfg.configured, '后端应已配置模型').toBe(true)
    expect(cfg.worker_enabled, '工作线程应已启用').toBe(true)

    // 找一场没有 Agent 运行记录的会议(没有 run 时「分析会议」未被历史结果禁用)
    const meetings = await apiGet(request, token, '/api/meetings')
    let fresh = null
    for (const m of meetings) {
      const runs = await apiGet(request, token, `/api/meetings/${m.id}/runs`)
      if (!runs.length) { fresh = m; break }
    }
    expect(fresh, '应存在尚无分析记录的会议(基线里有待分析会议)').toBeTruthy()

    await loginAndOpen(page, '/#/ai', ADMIN)
    const panel = page.locator('#meeting-agent-panel')
    await expect(panel).toBeVisible()
    await expect(panel.locator('.small').first())
      .toHaveText('将会议转写和查询到的项目数据发送到已配置模型分析；生成的建议仍需人工审核。')

    await page.locator('#meeting-select').selectOption(fresh.id)
    await expect(panel).toHaveAttribute('data-meeting-id', fresh.id)
    await expect(page.locator('#agent-message')).toContainText('选择已保存会议后可开始分析')
    await expect(page.locator('#agent-start')).toBeEnabled()

    // 已配置的对照面:未选中会议时按钮不可用(config 之外还有前置条件)
    await page.locator('#meeting-select').selectOption('')
    await expect(page.locator('#agent-start')).toBeDisabled()
  })

  test('[FE-AI-03] 会议音频区可上传本地 mp3 并在列表出现,随后删除清理', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const meetings = await apiGet(request, token, '/api/meetings')
    const meeting = meetings.find(m => m.title === 'Java-会议验证') || meetings[0]
    const before = await apiGet(request, token, `/api/meetings/${meeting.id}/audio`)
    const fileName = `${uniq('QA-AUDIO')}.mp3`
    const localFile = writeTempMp3(fileName)
    page.on('dialog', d => d.accept())

    try {
      await loginAndOpen(page, '/#/ai', ADMIN)
      await page.locator('#meeting-select').selectOption(meeting.id)
      const recorder = page.locator('#meeting-recorder')
      await expect(recorder).toBeVisible()
      await expect(recorder.locator('.audio-row')).toHaveCount(before.length)
      if (!before.length) await expect(recorder).toContainText('暂无音频。')

      // 选择本地 .mp3 提交(真实 multipart 上传;不真录音)
      await recorder.locator('.file-pick input[type=file]').setInputFiles(localFile)
      await expect(page.locator('#toast')).toContainText('已提交本地音频')

      const rows = recorder.locator('.audio-row')
      await expect(rows).toHaveCount(before.length + 1)
      const mine = rows.filter({ has: page.locator('b', { hasText: fileName }) })
      await expect(mine).toHaveCount(1)
      await expect(mine).toContainText('本地上传')
      await expect(mine.locator('.audio-meta .small')).toContainText('sha256')
      await expect(recorder.locator('.h-sec').last()).toContainText(`会议已有音频（${before.length + 1}）`)

      // 落库 + 回放接口可取到字节
      const after = await apiGet(request, token, `/api/meetings/${meeting.id}/audio`)
      const added = after.find(a => a.filename === fileName)
      expect(added, '音频应已落库').toBeTruthy()
      expect(added.source).toBe('upload')
      const play = await request.get(`${API}${added.url}`, { headers: authHeaders(token) })
      expect(play.status(), '带鉴权的回放接口应返回 200').toBe(200)
      expect((await play.body()).subarray(0, 3).toString('latin1')).toBe('ID3')

      // 删除清理(删除按钮需 admin/owner,当前为 admin)
      await mine.locator('button.danger').click()
      await expect(page.locator('#toast')).toContainText('录音已删除')
      await expect(rows).toHaveCount(before.length)
      const finalList = await apiGet(request, token, `/api/meetings/${meeting.id}/audio`)
      expect(finalList.map(a => a.id).sort()).toEqual(before.map(a => a.id).sort())
    } finally {
      const left = await apiGet(request, token, `/api/meetings/${meeting.id}/audio`)
      for (const a of left.filter(a => a.filename === fileName)) {
        await apiSend(request, token, 'DELETE', `/api/audio/${a.id}`, undefined)
      }
      fs.rmSync(localFile, { force: true })
    }
  })
})

/* ==================================================================================
 * FE-OFF · 离线演示模式
 * ================================================================================== */
test.describe('FE-OFF 离线演示模式', () => {
  test('[FE-OFF-01] API 指向死端口且存在旧基线缓存时,仍渲染 US01–US37 而不是崩溃', async ({ page }) => {
    const errors = []
    page.on('pageerror', e => errors.push(e.message))

    /* 先钉住 ENV-D02 的修复前提:页面初始**不存在** window.__AICAP_API_BASE__。
       此前 index.html 有一行内联脚本硬编码该全局,它有两个后果:在 addInitScript 之后执行
       会覆盖注入值(FE-OFF-01 只能改用 Object.defineProperty 不可写属性变通),
       且让 .env.* 的 VITE_API_BASE 永远读不到。现在该行已删除,由 client.js 三级回退解析。 */
    await page.goto('/#/board')
    expect(await page.evaluate(() => window.__AICAP_API_BASE__),
      'index.html 不得再硬编码 __AICAP_API_BASE__(否则 VITE_API_BASE 永远失效)').toBeUndefined()

    /* 把 API 基址指到无人监听的端口 + 预热一份旧基线(M01–M23)缓存。
       修复后这里用**普通可写赋值**即可生效;若那行内联脚本回归,赋值会被覆盖、
       baseUrl 变回 8080、健康检查成功 → 本用例随即失败,故它同时充当回归锚点。 */
    await page.addInitScript(() => {
      window.__AICAP_API_BASE__ = 'http://127.0.0.1:59999'
    })
    await page.goto('/#/board')
    await page.evaluate(() => {
      localStorage.clear()
      localStorage.setItem('aiguanli-pixel-stories-v1', JSON.stringify([
        { id: 'M01', activity: 1, sprint: 1, title: '旧基线故事', description: 'd', acceptance: 'a', priority: 'Must', owner: 0, status: 0 }
      ]))
    })
    await page.reload()

    // 落在离线演示模式(健康检查失败 → 不弹登录)
    await expect(page.locator('#mode-chip')).toHaveText('离线 · 演示数据(点击重连)')
    await expect(page.locator('#login')).toBeHidden()
    await expect(page.locator('#savehint')).toContainText('自动保存本地')
    await expect(page.locator('#view-board')).toBeVisible()

    // 旧 M 编号缓存被识别并落回 US01–US37 种子,而不是渲染 M01
    await expect(page.locator('#mappanel')).toBeVisible()
    await expect(page.locator('#map .card')).toHaveCount(37)
    const ids = await page.locator('#map .card').evaluateAll(els => els.map(e => e.dataset.id))
    expect(ids).toContain('US01')
    expect(ids).toContain('US37')
    expect(ids.filter(id => /^M\d+$/.test(id)), '不应渲染旧基线 M 编号').toEqual([])
    await expect(page.locator('#hintcount')).toContainText('已同步 37 条真实故事（US01–US37 基线）')

    // 其它视图在离线态同样可用(不因 API 不可达而崩溃)
    await page.goto('/#/gantt')
    await expect(page.locator('.grow.parent')).toHaveCount(12)
    await expect(page.locator('.grow.mgmt')).toHaveCount(4)
    await page.goto('/#/members')
    await expect(page.locator('#member-grid .mcard')).toHaveCount(5)
    // 离线时成员画像板块给出离线说明而不是报错
    await expect(page.locator('#member-profiles')).toHaveCount(0)
    await expect(page.locator('.view').getByText('离线演示模式：画像需连接后端读取')).toBeVisible()
    await page.goto('/#/uml')
    await expect(page.locator('.usecase-svg .uc-hit')).toHaveCount(15)
    await expect(page.locator('.sequence-svg')).toContainText('Spring Boot 后端')

    expect(errors, '离线演示不应抛出未捕获异常').toEqual([])
  })
})
