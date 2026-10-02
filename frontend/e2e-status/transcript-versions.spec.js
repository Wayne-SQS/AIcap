import { test, expect } from '@playwright/test'
const panel = page => page.getByRole('region', { name: '转写版本与人工核对', exact: true })
const draft = { audio: { meeting_id:'m1', audio_id:'a1', sha256:'a'.repeat(64), byte_size:123 }, duration_ms:2000, language:'zh', status:'draft', diarization_status:'not_available', storage_status:'not_saved', requires_human_review:true,
  text:'登陆接口待确认。', segments:[{segment_id:'S1',start_ms:0,end_ms:1900,text:'登陆接口待确认。',speaker_id:null}] }
async function setup(page, { role='member', loseConfirmation=false }={}) {
  let version=null, target=null; const saves=[], confirms=[]
  await page.addInitScript(()=>localStorage.setItem('aiguanli_token','fixture-token'))
  await page.route('**/meeting-ai/api/**',route=>route.fulfill({json:draft}))
  await page.route('http://127.0.0.1:8080/api/**',async route=>{
    const req=route.request(), path=new URL(req.url()).pathname; let data=[]
    if(path.endsWith('/health')) data={status:'ok'}
    if(path.endsWith('/auth/me')) data={id:1,role,display_name:role}
    if(path.endsWith('/agent/config')) data={configured:false}
    if(path.endsWith('/meetings')) data=[{id:'m1',title:'来源会议',transcript:'原文保留'},{id:'m2',title:'另一会议',transcript:'另一原文'},...(target?[target]:[])]
    if(path.endsWith('/meetings/m1/audio')) data=[{id:'a1',meeting_id:'m1',filename:'meeting.mp3',sha256:'a'.repeat(64),byte_size:123,source:'upload'}]
    if(path.endsWith('/transcript-versions')) {
      if(req.method()==='POST') { const body=req.postDataJSON(); saves.push(body); version ||= {id:'v1',meeting_id:'m1',audio_id:'a1',submitted_by:1,created_at:'2026-10-02',provenance:'caller_submitted',confirmation:null,...body}; data=version }
      else data=version?[version]:[]
    }
    if(path.endsWith('/confirm')) {
      const input=req.postDataJSON(); confirms.push(input)
      version.confirmation ||= {input,confirmed_by:1,confirmed_at:'2026-10-02',analysis_meeting_id:'derived'}
      target={id:'derived',title:input.title,transcript:input.text}; data=version
      if(loseConfirmation) { loseConfirmation=false; await route.fulfill({status:503,json:{detail:'response_lost'}}); return }
    }
    await route.fulfill({json:data})
  })
  await page.goto('/#/ai')
  return { saves, confirms }
}
async function save(page) {
  await page.getByRole('button',{name:'转写此音频'}).click()
  await panel(page).getByRole('button',{name:'保存转写版本'}).click()
  await expect(panel(page).getByRole('status')).toHaveText('转写版本已保存，可刷新恢复。')
}
async function prepare(page) {
  await panel(page).getByLabel('人工核对文本').fill('登录接口已核对。')
  await panel(page).getByLabel('我已对照录音核对文本，理解说话人未识别').check()
}
test('saved version survives reload and confirmed corrected text opens separate meeting',async({page})=>{
  const requests=await setup(page); await save(page); await page.reload()
  await expect(panel(page).getByLabel('人工核对文本')).toHaveValue(draft.text)
  await prepare(page); await panel(page).getByRole('button',{name:'确认并创建分析会议'}).click()
  await expect(panel(page)).toContainText('人工核对已保存，分析会议已创建。')
  expect(requests.saves).toHaveLength(1); expect(requests.confirms).toHaveLength(1)
  await expect(page.locator('#saved-transcript')).toHaveText('原文保留')
  await page.reload(); await expect(panel(page).getByLabel('人工核对文本')).toHaveValue('登录接口已核对。')
  await panel(page).getByRole('button',{name:'打开分析会议'}).click()
  await expect(page.locator('#saved-transcript')).toHaveText('登录接口已核对。')
  await expect(page.locator('#meeting-select')).toHaveValue('derived')
  await page.locator('#meeting-select').selectOption('m1')
  await expect(page.locator('#saved-transcript')).toHaveText('原文保留')
})
test('confirmation requires explicit acknowledgement',async({page})=>{
  const requests=await setup(page); await save(page)
  await panel(page).getByRole('button',{name:'确认并创建分析会议'}).click()
  await expect(panel(page).getByRole('alert')).toContainText('确认已核对录音')
  expect(requests.confirms).toHaveLength(0)
})
test('uncertain confirmation retries identical input and recovers saved result',async({page})=>{
  const requests=await setup(page,{loseConfirmation:true}); await save(page); await prepare(page)
  await panel(page).getByRole('button',{name:'确认并创建分析会议'}).click()
  await expect(panel(page).getByRole('alert')).toContainText('确认结果待核对')
  await expect(panel(page).getByLabel('人工核对文本')).toBeDisabled()
  await panel(page).getByRole('button',{name:'重试原确认提交'}).click()
  await expect(panel(page).getByRole('button',{name:'打开分析会议'})).toBeVisible()
  expect(requests.confirms).toHaveLength(2); expect(requests.confirms[1]).toEqual(requests.confirms[0])
})
test('same draft save retries use stable request id',async({page})=>{
  const requests=await setup(page); await save(page)
  await panel(page).getByRole('button',{name:'保存转写版本'}).click()
  await expect.poll(()=>requests.saves.length).toBe(2)
  expect(requests.saves[1]).toEqual(requests.saves[0])
})
test('viewer cannot submit version or human confirmation',async({page})=>{
  await setup(page,{role:'viewer'})
  await expect(panel(page)).toBeVisible()
  await expect(panel(page).getByRole('button',{name:'保存转写版本'})).toHaveCount(0)
  await expect(panel(page).getByRole('button',{name:'确认并创建分析会议'})).toHaveCount(0)
})
test('time-overlap suggestion can be changed and persists with diarization snapshot',async({page})=>{
  const requests=await setup(page)
  await page.route('**/diarization/run',route=>route.fulfill({json:{audio:draft.audio,engine:'sherpa-onnx-pyannote3-eres2net',duration_ms:2000,requested_num_speakers:2,speaker_count:2,status:'draft',identity_status:'anonymous_only',storage_status:'not_saved',requires_human_review:true,
    turns:[{start_ms:0,end_ms:400,speaker_id:'SPK1'},{start_ms:300,end_ms:1900,speaker_id:'SPK2'}]}}))
  await page.getByRole('button',{name:'转写此音频'}).click()
  const speaker=page.getByRole('region',{name:'说话人分离：meeting.mp3',exact:true})
  await speaker.getByLabel('预计人数').selectOption('2')
  await speaker.getByRole('button',{name:'分析说话人时间段'}).click()
  await expect(speaker).toContainText('检测 2 位匿名说话人')
  await panel(page).getByRole('button',{name:'保存转写版本'}).click()
  expect(requests.saves[0].draft.diarization).toEqual({engine:'sherpa-onnx-pyannote3-eres2net',duration_ms:2000,requested_num_speakers:2,speaker_count:2,identity_status:'anonymous_only',
    turns:[{start_ms:0,end_ms:400,speaker_id:'SPK1'},{start_ms:300,end_ms:1900,speaker_id:'SPK2'}]})
  await page.reload()
  await expect(panel(page).getByLabel(/S1/)).toHaveValue('SPK2')
  await expect(panel(page)).toContainText('跨说话人时间段：SPK1、SPK2')
  await panel(page).getByLabel(/S1/).selectOption('SPK1')
  await prepare(page); await panel(page).getByRole('button',{name:'确认并创建分析会议'}).click()
  const alignment=requests.confirms[0].speaker_alignment
  expect(alignment.audio_sha256).toBe('a'.repeat(64)); expect(alignment.turns).toHaveLength(2)
  expect(alignment.alignment_version).toBe(2)
  expect(alignment.assignments).toEqual([{segment_id:'S1',speaker_id:'SPK1',overlapping_speakers:['SPK1','SPK2']}])
  await page.reload(); await expect(panel(page).getByLabel(/S1/)).toHaveValue('SPK1')
})
test('late save does not leak into another meeting',async({page})=>{
  await setup(page); let release; const gate=new Promise(r=>release=r)
  await page.route('**/transcript-versions',async route=>{
    if(route.request().method()!=='POST') { await route.fallback(); return }
    await gate; await route.fallback()
  })
  await page.getByRole('button',{name:'转写此音频'}).click()
  const request=page.waitForRequest(r=>r.url().endsWith('/transcript-versions')&&r.method()==='POST')
  await panel(page).getByRole('button',{name:'保存转写版本'}).click(); await request
  await page.locator('#meeting-select').selectOption('m2')
  const response=page.waitForResponse(r=>r.url().endsWith('/transcript-versions')&&r.request().method()==='POST'); release(); await response
  await expect(panel(page)).toHaveCount(0); await expect(page.locator('#saved-transcript')).toHaveText('另一原文')
})
