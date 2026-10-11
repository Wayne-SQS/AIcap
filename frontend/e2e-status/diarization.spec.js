import { test, expect } from '@playwright/test'
const panel = page => page.getByRole('region',{name:'说话人分离：meeting.mp3',exact:true})
const result = () => ({ audio:{meeting_id:'m1',audio_id:'a1',sha256:'a'.repeat(64),byte_size:123}, engine:'sherpa-onnx-pyannote3-eres2net',duration_ms:3000,requested_num_speakers:2,speaker_count:2,status:'draft',identity_status:'anonymous_only',storage_status:'not_saved',requires_human_review:true,
  turns:[{start_ms:100,end_ms:1700,speaker_id:'SPK1'},{start_ms:1400,end_ms:2900,speaker_id:'SPK2'}] })
async function setup(page,role='member') {
  const requests=[]
  await page.addInitScript(()=>localStorage.setItem('aiguanli_token','fixture-token'))
  await page.route('**/meeting-ai/api/**',async route=>{ requests.push(route.request().postDataJSON()); await route.fulfill({json:result()}) })
  await page.route('http://127.0.0.1:8080/api/**',async route=>{
    const path=new URL(route.request().url()).pathname; let data=[]
    if(path.endsWith('/health')) data={status:'ok'}
    if(path.endsWith('/auth/me')) data={id:1,role,display_name:role}
    if(path.endsWith('/agent/config')) data={configured:false}
    if(path.endsWith('/meetings')) data=[{id:'m1',title:'会议',transcript:'原文'},{id:'m2',title:'其他',transcript:'其他原文'}]
    if(path.endsWith('/meetings/m1/audio')) data=[{id:'a1',meeting_id:'m1',filename:'meeting.mp3',sha256:'a'.repeat(64),byte_size:123,source:'upload'}]
    await route.fulfill({json:data})
  })
  await page.goto('/#/ai'); return requests
}
test('anonymous overlapping turns and speaker hint are displayed without identity claims',async({page})=>{
  const requests=await setup(page)
  await panel(page).getByLabel('预计人数').selectOption('2')
  await panel(page).getByRole('button',{name:'分析说话人时间段'}).click()
  await expect(panel(page)).toContainText('检测 2 位匿名说话人 · 2 个时间段')
  await expect(panel(page)).toContainText('0.10–1.70 秒 · SPK1')
  await expect(panel(page)).toContainText('1.40–2.90 秒 · SPK2')
  await expect(panel(page)).toContainText('不代表成员身份')
  expect(requests).toEqual([{audio_id:'a1',num_speakers:2}])
})
test('malformed identity and scope are rejected',async({page})=>{
  await setup(page)
  for(const fault of ['identity','scope']) {
    await page.route('**/diarization/run',route=>{const data=result(); if(fault==='identity') data.turns[0].speaker_id='Alice'; else data.audio.meeting_id='other'; return route.fulfill({json:data})},{times:1})
    await panel(page).getByRole('button',{name:'分析说话人时间段'}).click()
    await expect(panel(page).getByRole('alert')).toContainText('说话人分离失败')
  }
})
test('missing model is actionable and can retry',async({page})=>{
  await setup(page)
  await page.route('**/diarization/run',route=>route.fulfill({status:503,json:{detail:'diarization_model_not_installed'}}),{times:1})
  await panel(page).getByRole('button',{name:'分析说话人时间段'}).click()
  await expect(panel(page)).toContainText('尚未安装本地说话人分离模型')
  await panel(page).getByRole('button',{name:'分析说话人时间段'}).click()
  await expect(panel(page)).toContainText('检测 2 位匿名说话人')
})
test('viewer cannot initiate diarization',async({page})=>{
  await setup(page,'viewer'); await expect(panel(page)).toBeVisible()
  await expect(panel(page).getByRole('button',{name:'分析说话人时间段'})).toHaveCount(0)
})
test('late result cannot appear under another meeting',async({page})=>{
  await setup(page); let release; const gate=new Promise(r=>release=r)
  await page.route('**/diarization/run',async route=>{await gate; await route.fulfill({json:result()})})
  const request=page.waitForRequest('**/diarization/run')
  await panel(page).getByRole('button',{name:'分析说话人时间段'}).click(); await request
  await page.locator('#meeting-select').selectOption('m2')
  const response=page.waitForResponse('**/diarization/run'); release(); await response
  await expect(panel(page)).toHaveCount(0); await expect(page.locator('#saved-transcript')).toHaveText('其他原文')
})
