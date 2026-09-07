/** Agent UI integration against an explicit LOCAL TEST FIXTURE provider, not real DeepSeek.
 * Start qa/agent_provider_fixture.py on :9009 and an isolated backend on :8001.
 */
const {chromium} = require('playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '..');
const apiBase = 'http://127.0.0.1:8001';
const html = fs.readFileSync(path.join(repo, 'index.html'), 'utf8').replace(
  "const API_BASE='http://127.0.0.1:8000';", `const API_BASE='${apiBase}';`);
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  const errors = [];
  let checks = 0;
  const check = (ok, label) => {assert.ok(ok, label); checks++; console.log('PASS ' + label);};
  async function open(username) {
    const context = await browser.newContext();
    await context.grantPermissions(['local-network-access']);
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.route('**/index.html', r => r.fulfill({contentType: 'text/html', body: html}));
    await page.goto('http://127.0.0.1:8090/index.html');
    await page.fill('#login-user', username); await page.fill('#login-pass', '123456');
    await page.click('#login-form button[type="submit"]');
    await page.waitForFunction(() => document.querySelector('#savehint').textContent.includes('已连接后端'));
    await page.click('[data-view="ai"]'); await page.waitForSelector('#agent-start');
    return page;
  }
  async function save(page, title) {
    await page.fill('#meeting-save-form [name="title"]', title);
    await page.fill('#meeting-save-form [name="transcript"]', title + '\n负责人和Sprint下次再定。');
    await page.click('#meeting-save-form button');
    await page.waitForFunction(t => document.querySelector('#saved-transcript').textContent.includes(t), title);
    await page.waitForFunction(() => !document.querySelector('#agent-start').disabled);
  }
  async function start(page) {
    const response = page.waitForResponse(r => r.request().method() === 'POST' && /\/meetings\/[^/]+\/runs$/.test(r.url()));
    await page.click('#agent-start');
    const res = await response; assert.equal(res.status(), 200); return await res.json();
  }
  try {
    const page = await open('成员3');
    const suffix = Date.now();
    const title = 'Agent演示-' + suffix;
    await save(page, title);
    const run = await start(page);
    check(run.status === 'queued' || run.status === 'running', 'analysis creates durable job');
    await page.reload(); await page.click('[data-view="ai"]');
    await page.waitForFunction(() => document.querySelector('#meeting-agent-panel')?.textContent.includes('已保存 1 条建议'), null, {timeout: 30000});
    check((await page.textContent('#meeting-agent-panel')).includes('TEST FIXTURE'), 'fixture result clearly labelled as test data');
    check((await page.textContent('#meeting-agent-panel')).includes('search_stories') && (await page.textContent('#meeting-agent-panel')).includes('search_pool'), 'real project query events visible');
    check(await page.locator('#agent-start').isDisabled(), 'completed analysis cannot be started twice');
    await page.click('#agent-go-review');
    const card = page.locator('[data-meeting-suggestion]').filter({hasText: title});
    await card.waitFor();
    check((await card.textContent()).includes('运行已记录'), 'generated suggestion linked to recorded run');
    check(await card.locator('[data-meeting-review]').count() === 0, 'member cannot approve own Agent proposal');
    await page.click('[data-view="pool"]');
    check(!(await page.textContent('#pool-list')).includes(title), 'analysis has not written business data');
    const admin = await open('成员1'); await admin.click('[data-view="review"]');
    const adminCard = admin.locator('[data-meeting-suggestion]').filter({hasText: title});
    admin.once('dialog', d => d.accept('Agent联调审核'));
    await adminCard.locator('[data-decision="approve"]').click();
    await adminCard.getByText(/已创建需求/).waitFor();
    await admin.click('[data-view="pool"]');
    await admin.waitForFunction(t => document.querySelector('#pool-list').textContent.includes(t), title);
    check(true, 'human approval creates actual pool item');
    await page.click('[data-view="ai"]');
    await save(page, 'Agent演示 [FAIL_ONCE] ' + suffix);
    const failedRun = await start(page);
    await page.waitForSelector('#agent-retry', {timeout: 30000});
    check((await page.textContent('#meeting-agent-panel')).includes('HTTP 503'), 'provider error is visible');
    const retryResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/retry'));
    await page.click('#agent-retry');
    const retried = await (await retryResponse).json();
    check(retried.id === failedRun.id && retried.attempt === 2, 'retry reuses job and increments attempt');
    await page.waitForFunction(() => document.querySelector('#meeting-agent-panel')?.textContent.includes('已保存 1 条建议'), null, {timeout: 30000});
    check((await page.textContent('#meeting-agent-panel')).includes('第 1 次') && (await page.textContent('#meeting-agent-panel')).includes('第 2 次'), 'history retained after retry');
    await save(page, 'Agent演示 [BAD_EVIDENCE] ' + suffix);
    await start(page);
    await page.waitForSelector('#agent-retry', {timeout: 30000});
    check((await page.textContent('#meeting-agent-panel')).includes('引用无法'), 'invalid evidence is rejected visibly');
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join(';'));
    console.log(`${checks} Agent UI fixture checks passed`);
  } finally { await browser.close(); }
})().catch(err => {console.error(err); process.exitCode = 1;});
