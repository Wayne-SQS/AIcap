/** Run against an isolated test backend on :8001 and static frontend on :8090.
 * npm install playwright-core (or set NODE_PATH to an existing installation).
 * Uses system Edge, with normal browser security enabled.
 */
const {chromium} = require('playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '..');
const apiBase = process.env.MEETING_QA_API || 'http://127.0.0.1:8001';
const pageUrl = process.env.MEETING_QA_PAGE || 'http://127.0.0.1:8090/index.html';
const html = fs.readFileSync(path.join(repo, 'index.html'), 'utf8')
  .replace("const API_BASE='http://127.0.0.1:8000';", `const API_BASE='${apiBase}';`);

(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  const errors = [];
  let assertions = 0;
  function check(condition, label) { assert.ok(condition, label); assertions++; console.log('PASS ' + label); }
  async function open(username) {
    const context = await browser.newContext();
    await context.grantPermissions(['local-network-access']);
    const page = await context.newPage();
    page.on('pageerror', err => { errors.push(err.message); console.error('PAGE ERROR:', err.message); });
    page.on('console', msg => { if (msg.type() === 'error') console.error('CONSOLE:', msg.text()); });
    page.on('requestfailed', r => console.error('REQUEST FAILED:', r.url(), r.failure()));
    await page.route('**/index.html', route => route.fulfill({contentType: 'text/html', body: html}));
    await page.goto(pageUrl);
    await page.fill('#login-user', username);
    await page.fill('#login-pass', '123456');
    await page.click('#login-form button[type="submit"]');
    await page.waitForFunction(() => document.querySelector('#savehint').textContent.includes('已连接后端'));
    await page.click('[data-view="ai"]');
    await page.waitForSelector('#meeting-save-form');
    return page;
  }
  try {
    const member = await open('成员3');
    check((await member.textContent('#meeting')).includes('会议 Agent 尚未接入'), 'manual entry clearly labelled');
    const suffix = Date.now().toString();
    const title = '会议审核E2E-' + suffix;
    const transcript = '会议决定新增导出周报。另一个想法暂时不做。';
    await member.fill('#meeting-save-form [name="title"]', title);
    await member.fill('#meeting-save-form [name="transcript"]', transcript);
    await member.click('#meeting-save-form button');
    await member.waitForFunction(t => document.querySelector('#saved-transcript').textContent === t, transcript);
    check(true, 'meeting saved and transcript loaded');
    async function submit(name, evidence) {
      await member.fill('#meeting-proposal-form [name="title"]', name);
      await member.fill('#meeting-proposal-form [name="evidence"]', evidence);
      const request = member.waitForResponse(r => r.url() === apiBase + '/api/suggestions' && r.request().method() === 'POST');
      await member.click('#meeting-proposal-form button');
      const result = await request;
      assert.equal(result.status(), 200);
      return (await result.json()).id;
    }
    const approvedTitle = '周报 <img src=x onerror=alert(1)> ' + suffix;
    const sid = await submit(approvedTitle, '会议决定新增导出周报。');
    // Ensure UI response handling has finished before filling the next form.
    await member.waitForFunction(() => document.querySelector('#meeting-proposal-form [name="title"]').value === '');
    const rejectedId = await submit('暂缓需求-' + suffix, '另一个想法暂时不做。');
    await member.click('[data-view="review"]');
    await member.waitForSelector(`[data-meeting-suggestion="${sid}"]`);
    check(await member.locator('[data-meeting-review]').count() === 0, 'member cannot see review controls');
    check(await member.locator('#sug-list img').count() === 0, 'suggestion title is escaped');
    const admin = await open('成员1');
    await admin.click('[data-view="review"]');
    const approveButton = admin.locator(`[data-meeting-review="${sid}"][data-decision="approve"]`);
    admin.once('dialog', d => d.dismiss());
    await approveButton.click();
    check((await admin.textContent(`[data-meeting-suggestion="${sid}"]`)).includes('待审核'), 'cancel leaves pending');
    admin.once('dialog', d => d.accept('审核通过'));
    const approvedResponse = admin.waitForResponse(r => r.url().endsWith(`/suggestions/${sid}/review`));
    await approveButton.click();
    const approved = await (await approvedResponse).json();
    check(approved.execution_status === 'succeeded' && !!approved.pool_item_id, 'approve executes pool creation');
    await admin.waitForFunction(id => document.querySelector(`[data-meeting-suggestion="${id}"]`).textContent.includes('已创建需求'), sid);
    admin.once('dialog', d => d.accept('本轮不做'));
    const rejectedResponse = admin.waitForResponse(r => r.url().endsWith(`/suggestions/${rejectedId}/review`));
    await admin.click(`[data-meeting-review="${rejectedId}"][data-decision="reject"]`);
    const rejected = await (await rejectedResponse).json();
    check(rejected.status === 'rejected' && rejected.pool_item_id === null, 'reject executes no business change');
    await admin.click('[data-view="pool"]');
    await admin.waitForFunction(t => document.querySelector('#pool-list').textContent.includes(t), approvedTitle);
    check(!(await admin.textContent('#pool-list')).includes('暂缓需求-' + suffix), 'rejected item absent from pool');
    await member.reload();
    await member.click('[data-view="review"]');
    await member.waitForFunction(id => document.querySelector(`[data-meeting-suggestion="${id}"]`)?.textContent.includes('已创建需求'), sid);
    check((await member.textContent('#decision-log')).includes('审核通过'), 'review survives reload across users');
    await member.click('[data-view="pool"]');
    check((await member.textContent('#pool-list')).includes(approvedTitle), 'second user sees actual pool item');
    await member.click('#logout-btn');
    await member.click('[data-view="review"]');
    check(!(await member.textContent('#sug-list')).includes(sid), 'logout does not mix server suggestions into offline demo');
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join('; '));
    console.log(`${assertions} meeting UI checks passed`);
  } finally { await browser.close(); }
})().catch(err => { console.error(err); process.exitCode = 1; });
