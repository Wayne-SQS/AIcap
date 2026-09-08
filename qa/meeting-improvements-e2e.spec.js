/* Browser regression for review edits, explicit scheduling and unassigned owners. */
const {chromium} = require('playwright-core');
const assert = require('node:assert/strict');
const path = require('node:path');
const {flavorHtml, isVue, defaultPageUrl} = require('./e2e-helpers');
const root = path.resolve(__dirname,'..');
const base = 'http://127.0.0.1:8001';
const html = flavorHtml(base);
/* legacy 沿用 8091 独立静态源(与 ui-e2e 的 8090 隔离),旧版页面在 /legacy/;vue 走 dist 静态源(默认 8092) */
const pageUrl = process.env.AICAP_UI_URL || (isVue() ? defaultPageUrl() : 'http://127.0.0.1:8091/legacy/index.html');
(async () => {
 const browser = await chromium.launch({channel:'msedge',headless:true});
 let checks=0;
 const check=(value,label)=>{assert.ok(value,label);checks++;console.log('PASS '+label);};
 try {
  const context=await browser.newContext();
  await context.grantPermissions(['local-network-access']);
  const page=await context.newPage();
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/index.html',r=>r.fulfill({contentType:'text/html',body:html}));
  await page.goto(pageUrl);
  await page.fill('#login-user','成员1');await page.fill('#login-pass','123456');
  await page.click('#login-form button[type=submit]');
  await page.waitForFunction(()=>document.querySelector('#mode-chip')?.textContent.includes('已连接后端'));
  const title='交互回归-'+Date.now();
  const suggestion=await page.evaluate(async title=>{
   const m=await api('/api/meetings',{method:'POST',body:JSON.stringify({title,transcript:'希望增加导出周报功能。'})});
   return api('/api/suggestions',{method:'POST',body:JSON.stringify({meeting_id:m.id,client_request_id:crypto.randomUUID(),evidence:m.transcript,changes:{title,description:'原始范围'}})});
  },title);
  await page.evaluate(async()=>{await loadAll();renderAll();go('review');});
  const card=page.locator(`[data-meeting-suggestion="${suggestion.id}"]`);
  await card.locator('[data-edit-suggestion]').click();
  const dialog=page.locator('dialog[open]');
  await dialog.locator('[name=title]').fill(title+'-已修正');
  await dialog.locator('[name=description]').fill('仅导出本项目周报');
  await dialog.locator('[name=priority]').selectOption('Should');
  await dialog.locator('[name=reason]').fill('修正AI范围');
  await dialog.locator('[type=submit]').click();
  await page.waitForSelector('dialog[open]',{state:'hidden'});
  check((await card.textContent()).includes('最终采纳内容') && (await card.textContent()).includes('原始范围'),'original and approved revision both visible');
  const approved=await page.evaluate(id=>api('/api/suggestions/'+id),suggestion.id);
  check(approved.changes.title===title && approved.approved_changes.title===title+'-已修正','revision persisted without overwriting original');
  await page.click('[data-view=pool]');
  await page.locator(`[data-promote="${approved.pool_item_id}"]`).click();
  check(await dialog.locator('[name=sprint]').inputValue()==='','Sprint has no implicit default');
  check(await dialog.locator('[name=owner_id]').inputValue()==='','owner defaults to unassigned');
  await dialog.locator('[type=submit]').click();
  check(await dialog.count()===1,'missing Sprint blocks submit');
  await dialog.locator('[name=sprint]').selectOption('3');
  await dialog.locator('[name=activity]').selectOption('4');
  await dialog.locator('[type=submit]').click();
  await page.waitForSelector('dialog[open]',{state:'hidden'});
  const story=await page.evaluate(async title=>(await api('/api/stories')).find(s=>s.title===title),title+'-已修正');
  check(story.sprint===3 && story.owner_id===null && story.activity===4,'chosen Sprint and unassigned owner persisted');
  /* vue SPA:go() 为异步路由,先等 BoardView 挂载出过滤控件再设置(legacy 下等待立即满足) */
  await page.evaluate(()=>{go('board')});
  await page.waitForSelector('#sprint');
  await page.evaluate(()=>{for(const id of ['#sprint','#owner']){const el=document.querySelector(id);el.value='all';el.dispatchEvent(new Event('change'));}render();});
  const storyCard=page.locator(`#board [data-id="${story.id}"]`);
  check((await storyCard.textContent()).includes('未分配') && !(await storyCard.textContent()).includes('成员 1'),'unassigned story does not display member 1');
  await storyCard.click();
  check(await page.locator('#form [name=owner]').inputValue()==='','editor preserves unassigned owner');
  await page.locator('#form [name=title]').fill(title+'-仅改标题');
  await page.locator('#form button[type=submit]').click();
  await page.waitForSelector('#editor[open]',{state:'hidden'});
  check((await page.evaluate(async id=>(await api('/api/stories')).find(s=>s.id===id),story.id)).owner_id===null,'editing title does not assign member 1');
  await page.reload();
  await page.waitForFunction(()=>document.querySelector('#mode-chip')?.textContent.includes('已连接后端'));
  await page.evaluate(()=>go('review'));
  check((await page.locator(`[data-meeting-suggestion="${suggestion.id}"]`).textContent()).includes('最终采纳内容'),'revision survives reload');
  check(errors.length===0,'no browser JavaScript errors');
  console.log(`${checks} interaction checks passed`);
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
