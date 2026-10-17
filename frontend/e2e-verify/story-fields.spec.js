import { test, expect } from '@playwright/test'

test('故事的角色、目标、价值和验收条件分别保存并在刷新后回显', async ({ page }) => {
  let story = {
    id: 'US03', title: '需求基线',
    description: '作为负责人，我希望维护故事，以便形成需求基线',
    role: '负责人', goal: '维护故事', value: '形成需求基线', acceptance: '字段分别保存',
    epic_id: 2, priority: 'Must', sprint: 1, activity: 2, status: 0, owner_id: 1
  }
  let epics = [
    { id: 1, code: 'E1', name: '用户管理与权限', description: '' },
    { id: 2, code: 'E2', name: '任务与协作', description: '' },
    { id: 3, code: 'E3', name: '数据与分析', description: '' },
    { id: 4, code: 'E4', name: 'AI 智能体与人机协作', description: '' }
  ]
  const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.addInitScript(() => localStorage.setItem('aiguanli_token', 'test-token'))
  await page.route('**/api/health', route => route.fulfill(json({})))
  await page.route('**/api/auth/me', route => route.fulfill(json({ id: 1, display_name: '测试负责人', role: 'admin', can_write: true })))
  await page.route('**/api/projects', route => route.fulfill(json([{ id: 1, name: '需求基线项目', member_role: 'owner' }])))
  await page.route('**/api/auth/users', route => route.fulfill(json([{ id: 1, display_name: '测试负责人', role: 'admin', capacity_hours: 10 }])))
  await page.route('**/api/stories/logs', route => route.fulfill(json([])))
  await page.route('**/api/stories/US03', async route => {
    expect(route.request().method()).toBe('PATCH')
    story = { ...story, ...route.request().postDataJSON() }
    await route.fulfill(json(story))
  })
  await page.route('**/api/stories', route => route.fulfill(json([story])))
  await page.route('**/api/epics/*', async route => {
    const id = Number(route.request().url().split('/').pop())
    if (route.request().method() === 'PATCH') {
      epics = epics.map(epic => epic.id === id ? { ...epic, ...route.request().postDataJSON() } : epic)
      await route.fulfill(json(epics.find(epic => epic.id === id)))
    } else await route.fulfill(json({ ok: true }))
  })
  await page.route('**/api/epics', async route => {
    if (route.request().method() === 'POST') {
      const id = Math.max(...epics.map(epic => epic.id)) + 1
      const epic = { id, code: 'E' + id, ...route.request().postDataJSON() }
      epics.push(epic)
      await route.fulfill(json(epic))
    } else await route.fulfill(json(epics))
  })
  for (const path of ['tasks', 'pool', 'milestones'])
    await page.route(`**/api/${path}`, route => route.fulfill(json([])))
  await page.route('**/api/dashboard', route => route.fulfill(json({ total: 1, done: 0, doing: 0, todo: 1, percent: 0, by_sprint: [] })))

  await page.goto('/#/board')
  await page.locator('#manage-epics').click()
  const manager = page.locator('#epic-manager')
  await expect(manager).toBeVisible()
  await manager.locator('input[name=epic_name]').fill('交付保障')
  await manager.locator('textarea[name=epic_description]').fill('新增验收范围')
  await manager.locator('button[type=submit]').click()
  await expect(manager).toContainText('E5 · 交付保障')
  await manager.locator('li').filter({ hasText: 'E5 · 交付保障' }).getByRole('button', { name: '编辑' }).click()
  await manager.locator('input[name=epic_name]').fill('交付协作')
  await manager.locator('button[type=submit]').click()
  await expect(manager).toContainText('E5 · 交付协作')
  await manager.getByRole('button', { name: '关闭' }).click()
  await page.locator('#boardtab').click()
  await expect(page.locator('#boardpanel')).toBeVisible()
  await page.locator('#board .card[data-id="US03"]').click()
  const editor = page.locator('#editor')
  await expect(editor.locator('input[name=role]')).toHaveValue('负责人')
  await expect(editor.locator('input[name=goal]')).toHaveValue('维护故事')
  await expect(editor.locator('input[name=value]')).toHaveValue('形成需求基线')
  await expect(editor.locator('textarea[name=acceptance]')).toHaveValue('字段分别保存')
  await expect(editor.locator('select[name=epic_id]')).toHaveValue('2')
  await editor.locator('input[name=role]').fill('项目负责人')
  await editor.locator('input[name=goal]').fill('细化故事')
  await editor.locator('input[name=value]').fill('明确交付范围')
  await editor.locator('textarea[name=acceptance]').fill('可以逐字段查询')
  await editor.locator('select[name=epic_id]').selectOption('5')
  await editor.locator('button[type=submit]').click()
  await expect(editor).toBeHidden()
  expect(story).toMatchObject({ role: '项目负责人', goal: '细化故事', value: '明确交付范围', acceptance: '可以逐字段查询', epic_id: 5 })

  await page.reload()
  await page.locator('#boardtab').click()
  await page.locator('#board .card[data-id="US03"]').click()
  await expect(editor.locator('input[name=role]')).toHaveValue('项目负责人')
  await expect(editor.locator('input[name=goal]')).toHaveValue('细化故事')
  await expect(editor.locator('input[name=value]')).toHaveValue('明确交付范围')
  await expect(editor.locator('textarea[name=acceptance]')).toHaveValue('可以逐字段查询')
  await expect(editor.locator('select[name=epic_id]')).toHaveValue('5')
  await editor.getByRole('button', { name: '取消' }).click()
  await page.locator('#manage-epics').click()
  await expect(manager.locator('li').filter({ hasText: 'E5 · 交付协作' }).getByRole('button', { name: '删除' })).toBeDisabled()
})
