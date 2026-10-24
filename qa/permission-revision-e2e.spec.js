import { test, expect } from '@playwright/test'

/**
 * 爱管理 · 发布就绪证据:US16 权限边界 / US17 故事修订与来源追溯
 *
 * 被测对象:真实 Java 后端(8080,开发库 AIcap)+ 真实 Vue3 前端(5173),无 mock。
 * 账号(密码 123456):李锐铭=admin、成员5=viewer。
 * 故事/T 任务/P001 均为种子基线,只读;所有写操作在用例内唯一命名并自清理。
 *
 * 运行:cd qa && npx playwright test permission-revision-e2e.spec.js --config=playwright.config.js
 */

const API = process.env.QA_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = '123456'
const PROJECT_ID = 'P001'

const ADMIN = '李锐铭'
const VIEWER = '成员5'

/** 唯一命名后缀:便于识别 QA 造的数据,便于失败后人工清理 */
let seq = 0
function uniq(tag) {
  seq += 1
  return `${tag}-${Date.now().toString(36)}-${seq}-${Math.random().toString(36).slice(2, 6)}`
}

/* ==================================================================================
 * 公共助手(与 vue-baseline-e2e.spec.js 同写法;业务接口显式带 projectId)
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
  const res = await request.delete(
    `${API}/api/stories/${id}?projectId=${PROJECT_ID}&undone=keep`,
    { headers: authHeaders(token) }
  )
  expect([200, 404], `清理故事 ${id} 应成功或已不存在`).toContain(res.status())
}

/** 打开页面并在登录弹窗里完成登录 */
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

/** 看板:切到「状态看板」页签 */
async function openBoardTab(page) {
  await page.locator('#boardtab').click()
  await expect(page.locator('#boardpanel')).toBeVisible()
}

/** 造一条唯一命名的故事,返回后端分配的 code */
async function createStory(request, token, title) {
  const created = await apiSend(request, token, 'POST', `/api/stories?projectId=${PROJECT_ID}`, {
    title,
    description: 'QA 发布证据用例造数',
    acceptance: '用例结束时清理',
    priority: 'Could',
    sprint: 1,
    activity: 2,
    status: 0,
    owner_id: null
  })
  expect(created.status, 'admin 造数:创建故事应 200').toBe(200)
  return created.body.id
}

/* ==================================================================================
 * US16 · 权限边界主旅程
 * ================================================================================== */
test.describe('US16 权限边界', () => {
  test('US16 权限边界主旅程：viewer 只读、写被拦、跨项目不串数据', async ({ page, request }) => {
    const adminToken = await apiLogin(request, ADMIN)
    const viewerToken = await apiLogin(request, VIEWER)

    /* 1) viewer 身份:role=viewer,can_write=false */
    const me = await apiGet(request, viewerToken, '/api/auth/me')
    expect(me.role, 'viewer 角色应为 viewer').toBe('viewer')
    expect(me.can_write, 'viewer 的 can_write 应为 false').toBe(false)
    console.log('[US16] /api/auth/me = ' + JSON.stringify(me))

    /* 2) viewer 读可见:能读到 P001 的故事列表 */
    const viewerStories = await apiGet(request, viewerToken, `/api/stories?projectId=${PROJECT_ID}`)
    expect(viewerStories.length, 'viewer 应能读到故事列表').toBeGreaterThan(0)
    const seedCode = viewerStories[0].id
    const seedTitle = viewerStories[0].title
    console.log(`[US16] viewer 读到 ${viewerStories.length} 条故事,第一条 ${seedCode}=${seedTitle}`)

    /* 3) viewer 三类写操作全被拦,且不落地 */
    const before = await apiGet(request, adminToken, `/api/stories?projectId=${PROJECT_ID}`)

    const blockedTitle = uniq('QA-US16-VIEWER-POST-BLOCKED')
    const post = await apiSend(request, viewerToken, 'POST', `/api/stories?projectId=${PROJECT_ID}`, {
      title: blockedTitle,
      description: 'viewer 不允许创建',
      acceptance: '应被 403 拒绝',
      priority: 'Could',
      sprint: 1,
      activity: 2,
      status: 0,
      owner_id: null
    })
    expect(post.status, 'viewer POST /api/stories 应 403').toBe(403)

    const patchTitle = uniq('QA-US16-VIEWER-PATCH-BLOCKED')
    const patch = await apiSend(request, viewerToken, 'PATCH',
      `/api/stories/${seedCode}?projectId=${PROJECT_ID}`, { title: patchTitle })
    expect(patch.status, `viewer PATCH /api/stories/${seedCode} 应 403`).toBe(403)

    const del = await apiSend(request, viewerToken, 'DELETE',
      `/api/stories/${seedCode}?projectId=${PROJECT_ID}&undone=keep`)
    expect(del.status, `viewer DELETE /api/stories/${seedCode} 应 403`).toBe(403)

    console.log('[US16] viewer 写被拦: POST=' + post.status + ' ' + JSON.stringify(post.body)
      + ' | PATCH=' + patch.status + ' ' + JSON.stringify(patch.body)
      + ' | DELETE=' + del.status + ' ' + JSON.stringify(del.body))

    /* 三条都未落地:admin 重新读,故事集合与种子标题不变 */
    const after = await apiGet(request, adminToken, `/api/stories?projectId=${PROJECT_ID}`)
    expect(after.map(s => s.id).sort(), 'viewer 写操作不得改变故事集合').toEqual(before.map(s => s.id).sort())
    expect(after.find(s => s.id === seedCode).title, 'viewer 改标题不得落地').toBe(seedTitle)
    expect(after.some(s => s.title === blockedTitle), 'viewer 创建的故事不得存在').toBe(false)

    /* 4) admin 同样的写操作可以落地,并立即清理 */
    let adminStoryId = null
    try {
      const adminTitle = uniq('QA-US16-ADMIN-CREATE')
      adminStoryId = await createStory(request, adminToken, adminTitle)
      expect(after.map(s => s.id), 'admin 新建的故事 code 应是新编号').not.toContain(adminStoryId)

      const delAdmin = await apiSend(request, adminToken, 'DELETE',
        `/api/stories/${adminStoryId}?projectId=${PROJECT_ID}&undone=keep`)
      expect([200, 204], 'admin 删除自己造的故事应成功').toContain(delAdmin.status)
      adminStoryId = null

      const restored = await apiGet(request, adminToken, `/api/stories?projectId=${PROJECT_ID}`)
      expect(restored.map(s => s.id).sort(), 'admin 造数已清理,故事集回到基线')
        .toEqual(before.map(s => s.id).sort())
    } finally {
      await cleanupStory(request, adminToken, adminStoryId)
    }

    /* 5) 跨项目不串数据:admin 与 viewer 的项目列表对比 */
    const adminProjects = await apiGet(request, adminToken, '/api/projects')
    const viewerProjects = await apiGet(request, viewerToken, '/api/projects')
    const adminIds = adminProjects.map(p => p.id)
    const viewerIds = viewerProjects.map(p => p.id)
    const onlyAdmin = adminIds.filter(id => !viewerIds.includes(id))
    if (onlyAdmin.length) {
      const crossRead = await apiSend(request, viewerToken, 'GET', `/api/stories?projectId=${onlyAdmin[0]}`)
      expect([403, 404], `viewer 读 ${onlyAdmin[0]} 的故事应被拒`).toContain(crossRead.status)
      console.log(`[US16] 跨项目样本 ${onlyAdmin[0]}: viewer GET=${crossRead.status}`)
    } else {
      // 基线里 admin/viewer 的可见项目集合完全相同,没有可用于越权探测的样本 → 降级为子集断言
      expect(viewerIds.every(id => adminIds.includes(id)), 'viewer 的项目列表应是 admin 的子集').toBe(true)
      console.log('[US16] 无可用的跨项目样本(admin=' + JSON.stringify(adminIds)
        + ' viewer=' + JSON.stringify(viewerIds) + '),已降级为子集断言')
    }

    /* 6) 真实 UI:viewer 看板的写入口不可用。
       走项目作用域路由:前端只有 /projects/:projectId/* 会把 currentProjectId 落到 P001
       (裸 /#/board 在全新浏览器上下文里没有项目选择,看板会是空的) */
    await loginAndOpen(page, `/#/projects/${PROJECT_ID}/board`, VIEWER)
    await expect(page.locator('#mode-chip')).toHaveText('在线 · 已连接后端')
    await expect(page.locator('#user-chip')).toHaveText('只读查看者 · 查看者')

    // 新建故事入口:禁用 + 给出只读原因
    await expect(page.locator('#new')).toBeVisible()
    await expect(page.locator('#new')).toBeDisabled()
    await expect(page.locator('#new')).toHaveAttribute('title', /只读/)

    // 编辑故事入口:卡片本身是 button,viewer 下 disabled
    const card = page.locator(`#map .card[data-id="${seedCode}"]`)
    await expect(card).toBeVisible()
    await expect(card).toBeDisabled()

    // 状态看板里的「＋ 添加故事」也全部禁用
    await openBoardTab(page)
    const addCols = page.locator('#board .addcol')
    await expect(addCols).toHaveCount(3)
    expect(await addCols.evaluateAll(els => els.every(e => e.disabled)),
      'viewer 下每列的「添加故事」都应 disabled').toBe(true)

    console.log('[US16] UI: #new disabled=' + (await page.locator('#new').isDisabled())
      + ', 卡片 disabled=' + (await card.isDisabled())
      + ', addcol disabled=' + (await addCols.evaluateAll(els => els.map(e => e.disabled).join(','))))
  })
})

/* ==================================================================================
 * US17 · 故事修订与来源追溯主旅程
 * ================================================================================== */
test.describe('US17 故事修订与来源追溯', () => {
  test('US17 故事修订主旅程：编辑留痕、日志回读、删除后仍可追溯', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN)
    const storyTitle = uniq('QA-US17-STORY')
    let storyId = null

    try {
      /* 1) 造一条唯一命名的故事 */
      storyId = await createStory(request, token, storyTitle)
      console.log(`[US17] 新建故事 ${storyId} title=${storyTitle}`)

      /* 2) 创建留痕:logs 里能找到 create 条目 */
      const logsAfterCreate = await apiGet(request, token, `/api/stories/logs?projectId=${PROJECT_ID}`)
      const createLog = logsAfterCreate.find(l =>
        l.log_type === 'create' && l.story_id === storyId && String(l.detail).includes(storyTitle))
      expect(createLog, '应有一条 create 日志指向新故事并含标题').toBeTruthy()
      console.log('[US17] create 日志字段 = ' + JSON.stringify(createLog))

      /* 3) 修订留痕:改标题 → edit;改状态 → move */
      const newTitle = uniq('QA-US17-STORY-EDITED')
      const titlePatch = await apiSend(request, token, 'PATCH',
        `/api/stories/${storyId}?projectId=${PROJECT_ID}`, { title: newTitle })
      expect(titlePatch.status, 'PATCH 标题应 200').toBe(200)
      expect(titlePatch.body.title, '标题应已更新').toBe(newTitle)

      const statusPatch = await apiSend(request, token, 'PATCH',
        `/api/stories/${storyId}?projectId=${PROJECT_ID}`, { status: 1 })
      expect(statusPatch.status, 'PATCH 状态应 200').toBe(200)
      expect(statusPatch.body.status, '状态应已更新为 1').toBe(1)

      const logsAfterEdit = await apiGet(request, token, `/api/stories/logs?projectId=${PROJECT_ID}`)
      const storyLogs = logsAfterEdit.filter(l => l.story_id === storyId)
      const editLog = storyLogs.find(l => l.log_type === 'edit')
      const moveLog = storyLogs.find(l => l.log_type === 'move')
      expect(editLog, '应有 edit 日志').toBeTruthy()
      expect(moveLog, '应有 move 日志').toBeTruthy()
      expect(String(editLog.detail), 'edit 日志应包含新标题').toContain(newTitle)
      expect(String(moveLog.detail), 'move 日志应可读(含标题)').toContain(newTitle)
      console.log('[US17] edit 日志 = ' + JSON.stringify(editLog))
      console.log('[US17] move 日志 = ' + JSON.stringify(moveLog))

      /* 4) 真实 UI:看板「变更记录（服务器审计日志）」面板最新一行是本故事 */
      await loginAndOpen(page, `/#/projects/${PROJECT_ID}/board`, ADMIN)
      const panel = page.locator('details.logpanel')
      await expect(panel).toBeVisible()
      const summary = panel.locator('summary')
      await expect(summary).toContainText('变更记录（服务器审计日志）')
      await summary.click()

      const latestRow = page.locator('#logbody .logrow').first()
      await expect(latestRow, '最新一条审计日志应是本故事的 move 记录').toContainText(storyId)
      await expect(latestRow).toContainText(moveLog.detail)
      console.log('[US17] 面板标题 = ' + (await summary.textContent()).trim()
        + ' | 最新行 = ' + (await latestRow.textContent()).trim())

      /* 5) 删除后审计仍可追溯 */
      const del = await apiSend(request, token, 'DELETE',
        `/api/stories/${storyId}?projectId=${PROJECT_ID}&undone=keep`)
      expect([200, 204], '删除故事应成功').toContain(del.status)

      const logsAfterDelete = await apiGet(request, token, `/api/stories/logs?projectId=${PROJECT_ID}`)
      const delLog = logsAfterDelete.find(l => l.log_type === 'del' && l.story_id === storyId)
      expect(delLog, '删除后仍应有 del 日志,且能读到故事 code').toBeTruthy()
      console.log('[US17] del 日志 = ' + JSON.stringify(delLog))

      /* 6) 收尾:故事确已不存在 */
      const finalStories = await apiGet(request, token, `/api/stories?projectId=${PROJECT_ID}`)
      expect(finalStories.some(s => s.id === storyId), '故事应已不存在').toBe(false)
      storyId = null
    } finally {
      await cleanupStory(request, token, storyId)
    }
  })
})
