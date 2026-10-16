import { test, expect } from '@playwright/test'

const javaBase = process.env.AICAP_LIVE_JAVA_BASE || 'http://127.0.0.1:8080'
const projectId = 'P002'

test('Sprint 2 UML traces two formal stories and restores edited sequence steps', async ({ browser, request }) => {
  const login = await request.post(`${javaBase}/api/auth/login`, {
    data: { username: '李锐铭', password: '123456' }
  })
  expect(login.status()).toBe(200)
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const entries = [
    { title: '角色权限核验故事', description: '作为管理员配置角色与权限', role: '管理员', goal: '管理权限' },
    { title: '会议协作核验故事', description: '作为成员管理会议纪要', role: '团队成员', goal: '保存会议结论' }
  ]
  const ids = []
  try {
    for (const entry of entries) {
      const created = await request.post(`${javaBase}/api/stories?projectId=${projectId}`, {
        headers, data: { ...entry, acceptance: '图中可追溯该故事并保存步骤文案', priority: 'Should',
          sprint: 2, activity: 2, status: 0, owner_id: 1 }
      })
      expect(created.status()).toBe(200)
      ids.push((await created.json()).id)
    }
    const context = await browser.newContext()
    try {
      const page = await context.newPage()
      await page.goto('/#/projects')
      await page.locator('#login-user').fill('李锐铭')
      await page.locator('#login-pass').fill('123456')
      await page.locator('#login-form').getByRole('button', { name: '登录', exact: true }).click()
      await expect(page.locator('#login')).not.toBeVisible()
      await page.getByRole('button', { name: '进入项目' }).click()
      await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/overview`))
      await page.goto(`/#/projects/${projectId}/uml`)
      const open = async (usecase, storyId, title) => {
        await page.locator('.usecase-svg .uc-hit').filter({ hasText: usecase }).click()
        await expect(page.locator('.uc-detail')).toContainText(`${storyId}`)
        await expect(page.locator('.uc-detail')).toContainText(title)
        await page.locator('.uc-detail').getByRole('button', { name: '查看故事时序' }).click()
        await expect(page.locator('.sequence-svg')).toHaveAttribute('aria-label', new RegExp(storyId))
        await expect(page.getByText(`来源：当前项目 ${projectId} 的正式故事 ${storyId}。`)).toBeVisible()
      }
      await open('配置角色与权限', ids[0], entries[0].title)
      await page.locator('.sequence-editor input').first().fill('人工核对后的权限步骤')
      await page.getByRole('button', { name: '保存步骤文案到当前浏览器' }).click()
      await page.reload()
      await open('配置角色与权限', ids[0], entries[0].title)
      await expect(page.locator('.sequence-editor input').first()).toHaveValue('人工核对后的权限步骤')
      await page.getByRole('button', { name: '返回登录协议图' }).click()
      await open('管理会议与协作事项', ids[1], entries[1].title)
      await expect(page.locator('.sequence-editor input').first()).not.toHaveValue('人工核对后的权限步骤')
    } finally { await context.close() }
  } finally {
    for (const id of ids) {
      const removed = await request.delete(`${javaBase}/api/stories/${id}?projectId=${projectId}&undone=keep`, { headers })
      expect(removed.status()).toBe(200)
    }
  }
})
