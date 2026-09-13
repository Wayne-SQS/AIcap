import { test, expect } from '@playwright/test'

/**
 * legacy 新版(mcc 提交 9b14ec5)内容移植验收(真实浏览器 + 真实后端 8080):
 * A. 数据基线:US01–US37 / Sprint 4+ / 真实姓名 / 「未分配」保留
 * B. 看板:默认故事地图、Sprint 4+ 列与筛选、卡面子任务加权徽章(血缘口径保留)
 * C. 甘特图:父卡血缘行保留 + 点击任务条详情(前置/后续高亮) + 任务编辑真实 PATCH 往返
 * D. 成员任务图:5 名真实成员、点卡片开抽屉(状态筛选/Esc 关闭)、负载图例、活动图 6 周×7 天、画像板块回归
 * E. 离线演示:新基线可用 + 旧基线(M01–M23)缓存自动落回种子
 */

const ADMIN = '李锐铭'
const PASSWORD = '123456'

async function login(page, hash = '/#/board') {
  await page.goto(hash)
  const dialog = page.locator('#login')
  await expect(dialog).toBeVisible()
  await page.locator('#login-user').fill(ADMIN)
  await page.locator('#login-pass').fill(PASSWORD)
  await page.locator('#login-form button[type=submit]').click()
  await expect(dialog).toBeHidden()
}

test('数据基线与看板:故事地图默认 + Sprint 4+ + 真实姓名 + 血缘徽章', async ({ page }) => {
  await login(page, '/#/board')

  // 默认进入故事地图
  await expect(page.locator('#mappanel')).toBeVisible()
  await expect(page.locator('#boardpanel')).toBeHidden()

  // 头部信息与切片
  const hint = await page.locator('#hintcount').textContent()
  expect(hint).toContain('US01–US37')
  const sprintHeads = page.locator('#map .sprinthead')
  await expect(sprintHeads).toHaveCount(4)
  await expect(sprintHeads.nth(3)).toContainText('Sprint 4+')
  const mapText = await page.locator('#map').innerText()
  for (const id of ['US09', 'US15', 'US21']) expect(mapText, `Sprint 4+ 切片应含 ${id}`).toContain(id)

  // 卡片负责人 = 真实姓名;地图卡片带 Sprint 标注
  const us01 = page.locator('#map .card[data-id="US01"]')
  const us01Text = await us01.innerText()
  expect(us01Text).toContain('李锐铭')
  expect(us01Text).toMatch(/Sprint\s*1/)

  // Sprint 4+ 筛选:只剩 3 条后续路线故事
  await page.locator('#sprint').selectOption('4plus')
  await expect(page.locator('#map .card')).toHaveCount(3)
  const ids = await page.locator('#map .card').evaluateAll(els => els.map(e => e.dataset.id).sort())
  expect(ids).toEqual(['US09', 'US15', 'US21'])

  // 回到全部 Sprint → 状态看板:血缘徽章仍在(US01 挂 T03,未完成 → 0/1)
  await page.locator('#sprint').selectOption('all')
  await page.locator('#boardtab').click()
  await expect(page.locator('#boardpanel')).toBeVisible()
  const cards = page.locator('#board .card')
  await expect(cards).toHaveCount(37)
  const badge = (await page.locator('#board .card[data-id="US01"] .subprog').textContent())?.trim()
  expect(badge).toContain('0/1')
  const noBadge = await page.locator('#board .card[data-id="US05"] .subprog').count()
  expect(noBadge, '无子任务卡不应有徽章').toBe(0)
  console.log('[board] hint=' + hint + ' badge(US01)=' + badge + ' sprint4+=' + JSON.stringify(ids))

  // 负责人筛选使用真实姓名(第 5 位只读查看者按 display_name 展示)
  const ownerOptions = await page.locator('#owner option').allTextContents()
  expect(ownerOptions.join(',')).toContain('李锐铭')
  expect(ownerOptions.join(',')).toContain('只读查看者')
})

test('甘特图:父卡血缘 + 点击详情(前置/后续) + 任务编辑真实往返', async ({ page }) => {
  await login(page, '/#/gantt')

  // 血缘行保留:12 张挂卡 + 4 条管理任务
  await expect(page.locator('.grow.parent')).toHaveCount(12)
  await expect(page.locator('.grow.mgmt')).toHaveCount(4)
  const parentText = (await page.locator('.grow.parent .gbar.parent').first().textContent())?.trim()
  expect(parentText).toMatch(/^US\d+ · \d+%$/)
  await expect(page.locator('.gantt-head.gantt-sprint .sprinth')).toHaveCount(3)

  // 点击子任务条:T03 → 详情显示前置 T02 / 后续 T04,T05
  await page.locator('.gbar[data-gantt-task="task:T03"]').click()
  const detail = page.locator('#gantt-detail')
  await expect(detail).toContainText('T03')
  await expect(detail).toContainText('T02')
  await expect(detail).toContainText('前置任务')
  await expect(detail).toContainText('后续任务')
  await expect(page.locator('.gbar.predecessor')).toHaveCount(1)
  expect(await page.locator('.gbar.dependent').count()).toBeGreaterThan(0)

  // 再点一次同一条 = 取消选中
  await page.locator('.gbar[data-gantt-task="task:T03"]').click()
  await expect(detail).toContainText('点击任务条')
  await page.locator('.gbar[data-gantt-task="task:T03"]').click()

  // 编辑任务弹窗:预填正确 + 客户端校验
  await page.locator('#gantt-detail button').filter({ hasText: '编辑任务' }).click()
  const dialog = page.locator('#task-editor')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('input[name=name]')).toHaveValue(/项目、成员、自定义权限/)
  await expect(dialog.locator('input[name=storyRef]')).toHaveValue('US01,US02')
  await expect(dialog.locator('input[name=dependsOn]')).toHaveValue('T02')
  await dialog.locator('input[name=dependsOn]').fill('T03')
  await dialog.locator('button[type=submit]').click()
  await expect(page.locator('#toast')).toContainText('任务不能依赖自身')
  await expect(dialog).toBeVisible()
  await dialog.locator('#task-cancel').click()
  await expect(dialog).toBeHidden()

  // 管理任务 T16:工时 8 → 9(真实 PATCH)→ 校验 UI 与后端一致 → 还原为 8
  await page.locator('.gbar[data-gantt-task="task:T16"]').click()
  await expect(detail).toContainText('T16')
  await page.locator('#gantt-detail button').filter({ hasText: '编辑任务' }).click()
  await expect(dialog).toBeVisible()
  await dialog.locator('input[name=hours]').fill('9')
  await dialog.locator('button[type=submit]').click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('#toast')).toContainText('已同步到服务器')
  await expect(detail).toContainText('9h')
  const persisted = await page.evaluate(async () => {
    const token = localStorage.getItem('aiguanli_token')
    const res = await fetch('http://127.0.0.1:8080/api/tasks', { headers: { Authorization: 'Bearer ' + token } })
    const list = await res.json()
    return list.find(t => t.id === 'T16').hours
  })
  expect(persisted, '服务器应已写入 9h').toBe(9)

  // 周序校验:开始周 > 结束周
  await page.locator('#gantt-detail button').filter({ hasText: '编辑任务' }).click()
  await dialog.locator('select[name=weekStart]').selectOption('5')
  await dialog.locator('select[name=weekEnd]').selectOption('2')
  await dialog.locator('button[type=submit]').click()
  await expect(page.locator('#toast')).toContainText('开始周不能晚于结束周')

  // 还原 8h(W1–W6)
  await dialog.locator('input[name=hours]').fill('8')
  await dialog.locator('select[name=weekStart]').selectOption('1')
  await dialog.locator('select[name=weekEnd]').selectOption('6')
  await dialog.locator('button[type=submit]').click()
  await expect(dialog).toBeHidden()
  await expect(detail).toContainText('8h')
  const restored = await page.evaluate(async () => {
    const token = localStorage.getItem('aiguanli_token')
    const res = await fetch('http://127.0.0.1:8080/api/tasks', { headers: { Authorization: 'Bearer ' + token } })
    const list = await res.json()
    const t = list.find(x => x.id === 'T16')
    return [t.hours, t.week_start, t.week_end].join('/')
  })
  expect(restored).toBe('8/1/6')
  console.log('[gantt] parent=' + parentText + ' T03 前后置高亮 ok;T16 8h→9h→8h 往返 ok')
})

test('成员任务图:5 名真实成员 + 任务抽屉 + 负载图例 + 活动图 + 画像回归', async ({ page }) => {
  await login(page, '/#/members')

  const cards = page.locator('#member-grid .mcard')
  await expect(cards).toHaveCount(5)
  const names = await cards.locator('h3').allTextContents()
  expect(names).toEqual(['李锐铭', '高思晗', '孙秋实', '罗子涵', '只读查看者'])
  // 负载档位:李锐铭 72h / 容量 60h → 超载
  await expect(cards.first().locator('.loadflag')).toHaveText('超载')

  // 点成员卡片 → 抽屉(任务明细)
  await cards.first().click()
  const drawer = page.locator('#member-drawer')
  await expect(drawer).toBeVisible()
  await expect(page.locator('#drawer-title')).toContainText('李锐铭')
  const rows = drawer.locator('.task-item')
  await expect(rows).toHaveCount(6)
  // 状态筛选:进行中 → 只剩 T03
  await drawer.locator('[data-member-filter="doing"]').click()
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('T03')
  await drawer.locator('[data-member-filter="all"]').click()
  await expect(rows).toHaveCount(6)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()

  // 热力图:周容量口径写进 tooltip
  const heatTitle = await page.locator('#heat .heat-cell').first().getAttribute('title')
  expect(heatTitle).toContain('周容量')
  await expect(page.locator('.load-legend span')).toHaveCount(4)
  await expect(page.locator('.load-note')).toContainText('计划工时')

  // 容量与分配:5 张卡,李锐铭 60h 容量
  const bw = page.locator('#bandwidth .mcard')
  await expect(bw).toHaveCount(5)
  await expect(bw.first()).toContainText('容量 60h')

  // 活动图:6 周 × 7 天 = 42 格 + 周标签 6 + 日标签 7
  const grid = page.locator('#contrib .contrib').first()
  await expect(grid.locator('.c-cell')).toHaveCount(42)
  await expect(grid.locator('.week-label')).toHaveCount(6)
  await expect(grid.locator('.day-label')).toHaveCount(7)
  await expect(page.locator('#contrib .contrib-note')).toContainText('样例数据')

  // 画像板块回归(本次未改,仍需可用)
  await expect(page.locator('#member-profiles .pcard')).toHaveCount(5)
  console.log('[members] names=' + JSON.stringify(names) + ' drawer任务=' + (await rows.count()) + ' 活动格=42')
})

test('UML:真实 SVG 用例图 + 时序图(Spring Boot 后端 / 真实 Java 路由)', async ({ page }) => {
  await login(page, '/#/uml')

  // 用例图:15 个用例 / 4 个角色 / 23 条关联 + 4 条图例线
  await expect(page.locator('.usecase-svg .uc-hit')).toHaveCount(15)
  await expect(page.locator('.usecase-svg .actor-hit')).toHaveCount(4)
  await expect(page.locator('.usecase-svg .association')).toHaveCount(27)
  // 用例名来自故事标题(响应式)
  const svgText = await page.locator('.usecase-svg').textContent()
  expect(svgText).toContain('项目与成员范围')
  expect(svgText).toContain('US01')

  // 点击用例 → 展开对应故事明细;再次点击关闭按钮收起
  await page.locator('.usecase-svg .uc-hit').first().click()
  const detail = page.locator('.uc-detail')
  await expect(detail).toBeVisible()
  await expect(detail).toContainText('US01')
  await expect(detail).toContainText('项目与成员范围')
  await page.locator('.uc-detail-close').click()
  await expect(detail).toBeHidden()

  // 悬停角色 → 其关联用例与连线高亮(声明式 class,不操作 DOM)
  await page.locator('.usecase-svg .actor-hit').nth(1).hover()
  expect(await page.locator('.usecase-svg .uc-hit.is-on').count()).toBeGreaterThan(3)
  expect(await page.locator('.usecase-svg .association.is-on').count()).toBeGreaterThan(3)
  await page.mouse.move(0, 0)

  // 时序图:参与者写 Spring Boot 后端,14 条消息
  const seqText = await page.locator('.sequence-svg').textContent()
  expect(seqText).toContain('Spring Boot 后端')
  expect(seqText).not.toContain('FastAPI')
  await expect(page.locator('.sequence-svg [data-seq-message]')).toHaveCount(14)
  await expect(page.locator('.sequence-svg .participant')).toHaveCount(5)
  console.log('[uml] 用例=15 角色=4 关联=27 消息=14 参与者=5')
})

test('离线演示:新基线 US01–US37 可用,旧基线缓存自动落回种子', async ({ page }) => {
  // 预置一份旧基线(M01–M23)缓存,验证不会被误用
  await page.goto('/#/board')
  await page.evaluate(() => {
    localStorage.clear()
    localStorage.setItem('aiguanli-pixel-stories-v1', JSON.stringify([
      { id: 'M01', activity: 1, sprint: 1, title: '旧基线故事', description: 'd', acceptance: 'a', priority: 'Must', owner: 0, status: 0 }
    ]))
  })
  await page.reload()
  await page.locator('#login-offline').click()
  await expect(page.locator('#mappanel')).toBeVisible()
  const board = page.locator('#map')
  await expect(board).toContainText('US01')
  const count = await page.locator('#map .card').count()
  expect(count, '离线应使用 US01–US37 共 37 条').toBe(37)

  // 离线甘特:本地任务数据可渲染,任务编辑走离线分支
  await page.goto('/#/gantt')
  await expect(page.locator('.grow.parent')).toHaveCount(12)
  await page.locator('.gbar[data-gantt-task="task:T16"]').click()
  await page.locator('#gantt-detail button').filter({ hasText: '编辑任务' }).click()
  const dialog = page.locator('#task-editor')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('input[name=storyRef]')).toHaveValue('US07,US24,US37')
  await dialog.locator('#task-cancel').click()
  console.log('[offline] 基线=' + count + ' 条,gantt 血缘行=12')
})
