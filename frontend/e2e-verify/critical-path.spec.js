import { test, expect } from '@playwright/test'

test('甘特图显示最长依赖链，并对循环依赖给出提示', async ({ page }) => {
  let tasks = [
    { id: 'T01', name: '设计', owner_id: 1, hours: 8, week_start: 1, week_end: 2, depends_on: '', status: 0 },
    { id: 'T02', name: '实现', owner_id: 1, hours: 8, week_start: 3, week_end: 4, depends_on: 'T01', status: 0 },
    { id: 'T03', name: '文档', owner_id: 1, hours: 2, week_start: 3, week_end: 3, depends_on: 'T01', status: 0 },
    { id: 'T04', name: '验收', owner_id: 1, hours: 4, week_start: 5, week_end: 5, depends_on: 'T02,T03', status: 0 },
    { id: 'T05', name: '独立工作', owner_id: 1, hours: 20, week_start: 1, week_end: 4, depends_on: '', status: 0 }
  ]
  const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.route('http://127.0.0.1:8080/api/**', route => route.fulfill(json([])))
  await page.addInitScript(() => { localStorage.setItem('aiguanli_token', 'test-token'); localStorage.setItem('aiguanli-current-project', 'P003') })
  await page.route('http://127.0.0.1:8080/api/health', route => route.fulfill(json({})))
  await page.route('http://127.0.0.1:8080/api/auth/me', route => route.fulfill(json({ id: 1, display_name: '测试负责人', role: 'admin', can_write: true })))
  await page.route('http://127.0.0.1:8080/api/projects', route => route.fulfill(json([{ id: 'P003', name: '路径验收项目', project_role: 'owner', can_write: true, can_manage: true }])))
  await page.route('http://127.0.0.1:8080/api/auth/users', route => route.fulfill(json([{ id: 1, display_name: '测试负责人', role: 'admin', capacity_hours: 10 }])))
  await page.route('http://127.0.0.1:8080/api/projects/P003/members', route => route.fulfill(json([{ user_id: 1, project_role: 'owner', capacity_hours: 60, weekly_capacity_hours: 10 }])))
  await page.route('http://127.0.0.1:8080/api/stories/logs*', route => route.fulfill(json([])))
  await page.route('http://127.0.0.1:8080/api/stories*', route => route.fulfill(json([])))
  await page.route('http://127.0.0.1:8080/api/tasks*', route => route.fulfill(json(tasks)))
  await page.route('http://127.0.0.1:8080/api/epics*', route => route.fulfill(json([])))
  await page.route('http://127.0.0.1:8080/api/pool*', route => route.fulfill(json([])))
  await page.route('http://127.0.0.1:8080/api/milestones*', route => route.fulfill(json([{ id: 'M01', name: '交付', week: 6, description: '', status: 'planned', related_task_ids: 'T04' }])))
  await page.route('http://127.0.0.1:8080/api/dashboard*', route => route.fulfill(json({ total: 0, done: 0, doing: 0, todo: 0, percent: 0, by_sprint: [], last_updated_at: null })))

  await page.goto('/#/projects/P003/gantt')
  await expect(page.locator('#gantt-critical-path')).toContainText('T01 → T02 → T04')
  await expect(page.locator('#gantt-critical-path')).toContainText('5 周')
  await expect(page.locator('#gantt')).toContainText('交付')

  tasks = [
    { ...tasks[0], depends_on: 'T02' },
    { ...tasks[1], depends_on: 'T01' }
  ]
  await page.reload()
  await expect(page.locator('#gantt-critical-path')).toHaveText('任务依赖存在循环，无法计算关键路径。')
})
