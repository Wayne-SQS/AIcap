import { test, expect } from '@playwright/test'

/**
 * 新需求验收(真实浏览器):
 * A. 成员画像:后端真实数据渲染(5 名成员、技术栈/能力/流程领域各不相同)、编辑弹窗可用
 * B. 录音:调用麦克风(虚拟设备)→ 浏览器内编码为真 MP3 → 提交到会议 → 出现在会议音频列表并可回放
 */

const ADMIN = '成员1'
const PASSWORD = '123456'

async function login(page) {
  // 多项目改造后项目号是业务接口的必填上下文,先预置项目号再登录
  await page.addInitScript(() => localStorage.setItem('aiguanli-current-project', 'P001'))
  await page.goto('/#/members')
  const dialog = page.locator('#login')
  await expect(dialog).toBeVisible()
  await page.locator('#login-user').fill(ADMIN)
  await page.locator('#login-pass').fill(PASSWORD)
  await page.locator('#login-form button[type=submit]').click()
  await expect(dialog).toBeHidden()
}

/* 画像不在成员任务图(/members),归 #view-profiles:办公室改版后旧的
   ProfileAgentView 已退役,画像页是唯一入口;卡片的类名也从 .pcard/.ptitle/.chip
   改为与 ProfilesView 对齐的 .profile-card/.profile-name/.profile-* 三套条目。 */
const PROFILE_ITEM = '.profile-tech-tag, .profile-capability, .profile-domain-item'

test('成员画像:真实后端数据 + 编辑弹窗', async ({ page }) => {
  await login(page)

  await page.goto('/#/profiles')
  await expect(page.locator('#view-profiles')).toBeVisible()

  const cards = page.locator('#member-profiles .profile-card')
  await expect(cards).toHaveCount(5)

  // 每张卡都必须有画像标题与三个维度条目(空维度会渲染为「暂无记录」)
  const titles = []
  const dimCounts = []
  for (let i = 0; i < 5; i++) {
    const card = cards.nth(i)
    const title = (await card.locator('.profile-name').textContent())?.trim() || ''
    titles.push(title)
    const chips = await card.locator(PROFILE_ITEM).count()
    dimCounts.push(chips)
    expect(title.length, `第 ${i + 1} 张卡应有画像标题`).toBeGreaterThan(0)
    expect(chips, `第 ${i + 1} 张卡应有技能条目`).toBeGreaterThan(5)
  }
  // 差异化:标题与三套维度条目数不能全都一样(组长要求"各成员不一样")
  expect(new Set(titles).size, '画像标题应各不相同').toBe(5)
  expect(new Set(dimCounts).size, '各成员条目数不应完全一致').toBeGreaterThan(1)
  console.log('[profiles] titles=' + JSON.stringify(titles) + ' chips=' + JSON.stringify(dimCounts))

  // 编辑弹窗:预填 + 可保存(admin 可改任意成员)
  const chipsBefore = await cards.first().locator(PROFILE_ITEM).count()
  await cards.first().locator('.profile-edit').click()
  const dialog = page.locator('#profile-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('input[placeholder*="技术负责人"]')).not.toHaveValue('')
  await dialog.locator('.dim').first().locator('.add').click()
  await dialog.locator('.dim').first().locator('.skill-row').last().locator('input').fill('临时验收技能')
  await dialog.locator('#profile-save').click()
  await expect(dialog).toBeHidden()
  await expect(cards.first().locator(PROFILE_ITEM)).toHaveCount(chipsBefore + 1)
  const rowsAfter = await cards.first().locator(PROFILE_ITEM).count()
  expect(rowsAfter, '保存后画像条目数应增加').toBe(chipsBefore + 1)

  // 还原:删掉刚加的临时技能
  await cards.first().locator('.profile-edit').click()
  await expect(dialog).toBeVisible()
  const rows = dialog.locator('.dim').first().locator('.skill-row')
  const n = await rows.count()
  for (let i = 0; i < n; i++) {
    const name = await rows.nth(i).locator('input').inputValue()
    if (name === '临时验收技能') { await rows.nth(i).locator('.del').click(); break }
  }
  await dialog.locator('#profile-save').click()
  await expect(dialog).toBeHidden()
  await expect(cards.first().locator(PROFILE_ITEM)).toHaveCount(chipsBefore)
  console.log('[profiles] edit+revert ok (chips ' + chipsBefore + ' -> ' + rowsAfter + ' -> ' + chipsBefore + ')')
})

test('录音:麦克风 → MP3 → 提交会议 → 回放', async ({ page }) => {
  await login(page)

  // 保存一个新会议,承载本次录音。会议工作区已收进 AI 办公室的同事工作台:
  // 直接进 /ai 只会落到办公室首页(抽屉关闭,表单不在 DOM),必须用 ?tab=meeting 展开工作台。
  await page.goto('/#/office?tab=meeting')
  await expect(page.locator('#meeting-save-form')).toBeVisible()
  const stamp = Date.now()
  const title = `录音验收会议 ${stamp}`
  await page.locator('#meeting-save-form input[name=title]').fill(title)
  await page.locator('#meeting-save-form textarea[name=transcript]').fill('本次会议用于验收网页麦克风录音与 mp3 提交。')
  await page.locator('#meeting-save-form button[type=submit]').click()
  /* 保存前下拉框可能已选中上一轮遗留会议, `toHaveValue(/.+/)` 会立刻通过并读到旧 id,
     导致后面删的是旧会议、本轮新会议留在库里。改为等「本次标题」的选项出现并被选中。 */
  const option = page.locator('#meeting-select option', { hasText: title })
  await expect(option).toHaveCount(1, { timeout: 20000 })
  const meetingId = await option.getAttribute('value')
  expect(meetingId, '新会议应有 id').toBeTruthy()
  await expect(page.locator('#meeting-select')).toHaveValue(meetingId)

  // RecorderPanel(#meeting-recorder)只在「语音会议」工作流下挂载:保存会议走的是
  // 默认的文本工作流,这里必须切到 audio 工位才拿得到录音区。
  await page.locator('#meeting-workflow-tab-audio').click()
  const recorder = page.locator('#meeting-recorder')
  await expect(recorder).toBeVisible()

  // 录音(虚拟麦克风;真实走 getUserMedia + MediaRecorder)
  await page.locator('#rec-start').click()
  await expect(page.locator('#rec-live, .rec-live')).toBeVisible()
  await page.waitForTimeout(3500)
  await page.locator('#rec-stop').click()

  // 浏览器内 lamejs 编码为真 mp3
  const clip = page.locator('.clip')
  await expect(clip).toBeVisible({ timeout: 60000 })
  const clipText = (await clip.locator('.clip-head').textContent()) || ''
  expect(clipText).toContain('已编码 MP3')
  const sizeMatch = clipText.match(/([\d.]+)\s*(KB|MB|B)/)
  expect(sizeMatch, '待提交录音应显示体积').not.toBeNull()
  const clipSrc = await clip.locator('audio').getAttribute('src')
  expect(clipSrc, '本地 mp3 应可试听(blob:)').toContain('blob:')
  console.log('[recorder] clip=' + clipText.trim())

  // 提交到会议
  await page.locator('#rec-submit').click()
  const rows = recorder.locator('.audio-row')
  await expect(rows).toHaveCount(1, { timeout: 60000 })
  const rowText = (await rows.first().textContent()) || ''
  expect(rowText).toContain('网页录音')
  expect(rowText).toContain('.mp3')
  expect(rowText).toMatch(/sha256\s+[0-9a-f]{10}/)
  console.log('[recorder] uploaded row=' + rowText.replace(/\s+/g, ' ').trim())

  // 回放:鉴权取字节 → objectURL
  await rows.first().getByRole('button', { name: '加载回放' }).click()
  await expect(rows.first().locator('audio')).toBeVisible({ timeout: 30000 })
  const playSrc = await rows.first().locator('audio').getAttribute('src')
  expect(playSrc).toContain('blob:')
  console.log('[recorder] playback ok src=' + playSrc.slice(0, 24) + '...')

  // 删除(管理员),并确认列表回到空
  page.once('dialog', d => d.accept())
  await rows.first().getByRole('button', { name: '删除' }).click()
  await expect(recorder.locator('.audio-row')).toHaveCount(0, { timeout: 30000 })
  console.log('[recorder] delete ok')

  // 清理本次验收用的会议:后端 DELETE /api/meetings/{id}(admin 可用),
  // 多项目改造后 projectId 是必填查询参数,漏了会 422 而非 404/200,
  // 否则每跑一轮就在开发库留一条「录音验收会议」垃圾数据
  const token = await page.evaluate(() => localStorage.getItem('aiguanli_token'))
  const gone = await page.request.delete(`http://127.0.0.1:8080/api/meetings/${meetingId}?projectId=P001`, {
    headers: { Authorization: 'Bearer ' + token }
  })
  expect([200, 404], '验收会议应被删除清理,实际 ' + gone.status() + ' ' + (await gone.text().catch(() => ''))).toContain(gone.status())
  console.log('[recorder] meeting cleaned up: ' + meetingId)
})
