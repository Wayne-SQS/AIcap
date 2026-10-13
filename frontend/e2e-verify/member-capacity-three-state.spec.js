import { test, expect } from '@playwright/test'

/**
 * US11 / US27 容量三态边界用例:零容量、未配置、正常,含空数据与跨周任务。
 *
 * 口径依据 `docs/项目侧交接_会议Agent接入_v0.1.md:56`:
 *   「无容量数据返回 unknown,**不当作零或无限容量**;容量为零时避免除零。」
 *
 * 断言锚点:
 *   - 容量 60 + 72h 跨周任务 → 档位「超载」,负载率 120%(不封顶)
 *   - 容量 0  + 10h 任务      → 档位「超载」,负载率「∞」(旧实现显示 0% / 充足)
 *   - 容量 null(空数据)      → 档位「未配置」,负载率「—」,热力格中性格,不判超载
 *   - `.load-legend` 仍为 4 档:未配置不是负载档位,不得混进图例
 *
 * 全程走路由打桩,不需要后端 8080;零容量/未配置在种子数据里不可达,只能这样构造。
 */

function taskRow(id, ownerId, hours, weekStart, weekEnd) {
  return {
    id, name: `任务 ${id}`, owner_id: ownerId, hours,
    week_start: weekStart, week_end: weekEnd,
    story_ref: '', kanban_card_id: null,
    estimated_hours: hours, task_type: 'feature', depends_on: '',
    priority: 'Should', status: 0, progress: 0, blocked: false,
    sprints: [1, 2, 3].filter(s => (s - 1) * 2 + 1 <= weekEnd && s * 2 >= weekStart)
  }
}

/* 必须给满 5 个用户:store 的 members 以 seed 的 5 人为底座,mergeMembers 只覆盖/补齐、不删多余的,
   少于 5 个会残留种子成员,data-member 下标就对不上了。 */
const USERS = [
  { id: 1, username: 'u1', display_name: '甲', role: 'admin', color: 'green', capacity_hours: 60 },
  { id: 2, username: 'u2', display_name: '乙', role: 'member', color: 'blue', capacity_hours: 0 },
  { id: 3, username: 'u3', display_name: '丙', role: 'member', color: 'pink', capacity_hours: null },
  { id: 4, username: 'u4', display_name: '丁', role: 'member', color: 'orange', capacity_hours: 60 },
  { id: 5, username: 'u5', display_name: '戊', role: 'viewer', color: 'gray', capacity_hours: 60 }
]

const TASKS = [
  taskRow('T01', 1, 72, 1, 6),   // 跨周:甲 72h / 容量 60h → 120%
  taskRow('T02', 2, 10, 1, 6),   // 零容量成员身上的 10h → ∞
  taskRow('T03', 2, 0, 1, 6)     // 空数据:0 工时的任务不应产生任何负载
]

test('容量三态:零容量判超载、未配置单列、正常档位不受影响', async ({ page }) => {
  await page.addInitScript(() => {
    window.__AICAP_API_BASE__ = 'http://mock.local'
    localStorage.setItem('aiguanli_token', 'mock-token')
  })
  await page.route('http://mock.local/**', async route => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({
        status: 204, headers: {
          'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, PATCH, DELETE, OPTIONS',
          'access-control-allow-headers': 'authorization, content-type'
        }
      })
      return
    }
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/health') body = { status: 'ok', db: true }
    else if (path === '/api/auth/me') body = USERS[0]
    else if (path === '/api/auth/users') body = USERS
    else if (path === '/api/stories') body = []
    else if (path === '/api/stories/logs') body = []
    else if (path === '/api/tasks') body = TASKS
    else if (path === '/api/pool') body = []
    else if (path === '/api/milestones') body = []
    else if (path === '/api/members/profiles') body = []
    else if (path === '/api/dashboard') body = {}
    await route.fulfill({
      status: 200, contentType: 'application/json', headers: {
        'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET, POST, PATCH, DELETE, OPTIONS',
        'access-control-allow-headers': 'authorization, content-type'
      }, body: JSON.stringify(body)
    })
  })

  await page.goto('/#/members')
  await expect(page.locator('#member-grid .mcard')).toHaveCount(5)

  /* ---- 成员卡档位:data-member 是 0 基成员下标(user.id - 1) ---- */
  await expect(page.locator('#member-grid .mcard[data-member="0"] .loadflag')).toHaveText('超载')   // 60h / 72h
  await expect(page.locator('#member-grid .mcard[data-member="1"] .loadflag')).toHaveText('超载')   // 0h  + 10h → ∞
  await expect(page.locator('#member-grid .mcard[data-member="2"] .loadflag')).toHaveText('未配置') // null

  /* ---- 容量与分配:负载率数字位 ---- */
  const bandwidth = page.locator('#bandwidth .mcard')
  await expect(bandwidth).toHaveCount(5)
  await expect(bandwidth.nth(0).locator('.bw-row .mono')).toHaveText('120%')  // 不封顶
  await expect(bandwidth.nth(1).locator('.bw-row .mono')).toHaveText('∞')     // 零容量满溢
  await expect(bandwidth.nth(2).locator('.bw-row .mono')).toHaveText('—')     // 未配置
  await expect(bandwidth.nth(0).locator('.role')).toContainText('容量 60h')
  await expect(bandwidth.nth(1).locator('.role')).toContainText('容量 0h')
  await expect(bandwidth.nth(2).locator('.role')).toContainText('容量 未配置')

  /* ---- 热力图:零容量格是超载色(hl3);未配置是中性格(hl-1),不冒充「低负载」 ---- */
  const heatRows = page.locator('#heat .heat-row')
  await expect(heatRows).toHaveCount(5)
  await expect(heatRows.nth(1).locator('.heat-cell').first().locator('.hrs')).toHaveClass(/hl3/)
  await expect(heatRows.nth(1).locator('.heat-cell').first()).toHaveAttribute('title', /周容量：0\.0h/)
  await expect(heatRows.nth(2).locator('.heat-cell').first().locator('.hrs')).toHaveClass(/hl-1/)
  await expect(heatRows.nth(2).locator('.heat-cell').first()).toHaveAttribute('title', /周容量：未配置/)

  /* ---- 未配置不是负载档位:图例仍是 4 档,不得被挤成 5 档 ---- */
  await expect(page.locator('.load-legend span')).toHaveCount(4)

  /* ---- 全程不得出现未捕获异常 ---- */
  const errors = []
  page.on('pageerror', e => errors.push(String(e)))
  await page.waitForTimeout(300)
  expect(errors).toEqual([])
})
