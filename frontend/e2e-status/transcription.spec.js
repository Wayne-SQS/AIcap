import { test, expect } from '@playwright/test'
const region = page => page.getByRole('region', { name: '音频转写：meeting.mp3', exact: true })
function draft() { return { audio: { meeting_id: 'm1', audio_id: 'a1', sha256: 'a'.repeat(64), byte_size: 123 }, duration_ms: 2000, status: 'draft', language: 'zh', diarization_status: 'not_available', storage_status: 'not_saved', requires_human_review: true, segments: [{ segment_id: 'S1', start_ms: 100, end_ms: 1800, text: '负责人待确认。', speaker_id: null,
  words:[{word_id:'S1W1',start_ms:100,end_ms:800,text:'负责人'},{word_id:'S1W2',start_ms:800,end_ms:1800,text:'待确认。'}] }], text: '负责人待确认。' } }
async function setup(page, role='admin') {
  const requests=[]
  await page.addInitScript(() => localStorage.setItem('aiguanli_token','fixture-token'))
  await page.route('**/meeting-ai/api/**', async route => {
    requests.push(route.request().postDataJSON()); await route.fulfill({ json: draft() })
  })
  await page.route('http://127.0.0.1:8080/api/**', async route => {
    const path=new URL(route.request().url()).pathname
    let data=[]
    if(path.endsWith('/health')) data={status:'ok'}
    if(path.endsWith('/auth/me')) data={id:1,role,display_name:role}
    if(path.endsWith('/agent/config')) data={configured:false}
    if(path.endsWith('/meetings')) data=[{id:'m1',title:'会议',transcript:'原有会议文本'},{id:'m2',title:'其他会议',transcript:'另一段文本'}]
    if(path.endsWith('/meetings/m1/audio')) data=[{id:'a1',meeting_id:'m1',filename:'meeting.mp3',sha256:'a'.repeat(64),byte_size:123,source:'upload'}]
    await route.fulfill({json:data})
  })
  await page.goto('/#/ai')
  return requests
}
test('transcription displays timed draft and unknown speaker without overwriting original',async({page})=>{
  const requests=await setup(page)
  await region(page).getByLabel('转写语言').selectOption('zh')
  await region(page).getByRole('button',{name:'转写此音频'}).click()
  await expect(region(page)).toContainText('0.10–1.80 秒 · 未知说话人')
  await expect(region(page).getByRole('textbox')).toHaveValue('负责人待确认。')
  await expect(page.locator('#saved-transcript')).toHaveText('原有会议文本')
  expect(requests).toEqual([{audio_id:'a1',language:'zh'}])
})
for(const fault of ['scope','timeline','words']) test(`transcription rejects malformed ${fault}`,async({page})=>{
  await setup(page)
  await page.route('**/transcription/run',route=>{ const data=draft(); if(fault==='scope') data.audio.meeting_id='other'; else if(fault==='timeline') data.segments[0].end_ms=3000; else data.segments[0].words[1].word_id='S1W9'; return route.fulfill({json:data}) })
  await region(page).getByRole('button',{name:'转写此音频'}).click()
  await expect(region(page).getByRole('alert')).toContainText('转写失败')
  await expect(region(page).getByRole('textbox')).toHaveCount(0)
})
test('transcription viewer has no initiation button',async({page})=>{
  await setup(page,'viewer'); await expect(region(page)).toBeAttached()
  await expect(region(page).getByRole('button',{name:'转写此音频'})).toHaveCount(0)
})
test('transcription no speech remains explicit empty result',async({page})=>{
  await setup(page)
  await page.route('**/transcription/run',route=>route.fulfill({json:{...draft(),segments:[],text:'',status:'no_speech'}}))
  await region(page).getByRole('button',{name:'转写此音频'}).click()
  await expect(region(page)).toContainText('未识别到可转写的语音')
})
test('transcription busy error allows retry',async({page})=>{
  await setup(page)
  await page.route('**/transcription/run',route=>route.fulfill({status:429,json:{detail:'stt_busy'}}),{times:1})
  await region(page).getByRole('button',{name:'转写此音频'}).click()
  await expect(region(page)).toContainText('正在处理另一段音频')
  await region(page).getByRole('button',{name:'转写此音频'}).click()
  await expect(region(page).getByRole('textbox')).toHaveValue('负责人待确认。')
})
test('late transcription response cannot appear in another meeting',async({page})=>{
  await setup(page)
  let release; const gate=new Promise(resolve=>{release=resolve})
  await page.route('**/transcription/run',async route=>{await gate; await route.fulfill({json:draft()})})
  const requested=page.waitForRequest('**/transcription/run')
  await region(page).getByRole('button',{name:'转写此音频'}).click(); await requested
  await page.locator('#meeting-select').selectOption('m2')
  const response=page.waitForResponse('**/transcription/run'); release(); await response
  await expect(region(page)).toHaveCount(0)
  await expect(page.locator('#saved-transcript')).toHaveText('另一段文本')
})
