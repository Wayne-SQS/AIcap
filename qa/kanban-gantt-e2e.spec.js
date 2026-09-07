/**
 * 爱管理 · 看板↔甘特血缘重构 E2E(Playwright)
 * =========================================
 * 运行前提(同 ui-e2e.spec.js):
 *  1) 离线套件(FE-KGN):静态服务器 8090 即可(API_BASE 指向无人监听端口)
 *  2) 在线套件(OE-KGN):后端 QA 实例 127.0.0.1:8001 连 AIcap_qa(先跑 reset_qa_db.py)
 *  3) node_modules 路径:NODE_PATH=%TEMP%\aiguanli-qa\node_modules
 *
 * 覆盖测试设计文档 v2「3. 前端测试用例(离线)」FE-KGN-01~09 与「4. 前后端联调」OE-KGN-01~03。
 * 在线用例全部设计为可逆(测后恢复 QA 库原状)。
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const PAGE_URL = process.env.AICAP_UI_URL || 'http://127.0.0.1:8090/index.html';
const QA = process.env.AICAP_QA_URL || 'http://127.0.0.1:8001';
const DEAD = 'http://127.0.0.1:59999';

const results = [];
let passed = 0, failed = 0;

function check(name, cond, extra = '') {
  if (cond) { passed++; results.push({ name, ok: true }); console.log('  ✔ ' + name); }
  else { failed++; results.push({ name, ok: false, extra }); console.log('  ✘ ' + name + (extra ? ' :: ' + extra : '')); }
}
async function waitFor(fn, ms = 6000, step = 80) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { try { if (await fn()) return true; } catch (e) {} await new Promise(r => setTimeout(r, step)); }
  return false;
}

async function openApp(browser, apiBase) {
  const ctx = await browser.newContext({ acceptDownloads: true });
  await ctx.grantPermissions(['local-network-access']);
  const page = await ctx.newPage();
  const html = fs.readFileSync(path.join(REPO, 'index.html'), 'utf8')
    .replace("const API_BASE='http://127.0.0.1:8000';", `const API_BASE='${apiBase}';`);
  await page.route('**/index.html', r => r.fulfill({ contentType: 'text/html', body: html }));
  await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });
  return { ctx, page };
}

async function clickCard(page, storyId) { await page.click(`#board .card[data-id="${storyId}"]`); await page.waitForSelector('#editor[open]'); }
function onConfirm(page) { page.once('dialog', d => d.accept()); }
/** 读页面全局 stories/tasks(顶层 const 在全局词法环境,evaluate 可访问) */
const taskW = (page, id) => page.evaluate(tid => tasks.find(t => t.id === tid).w, id);
const taskCard = (page, id) => page.evaluate(tid => tasks.find(t => t.id === tid).card, id);
const taskStatus = (page, id) => page.evaluate(tid => tasks.find(t => t.id === tid).status, id);

async function suiteOffline(browser) {
  console.log('\n== 血缘 · 离线演示模式(FE-KGN)==');
  const { ctx, page } = await openApp(browser, DEAD);
  const jsErr = []; page.on('pageerror', e => jsErr.push(e.message));
  await waitFor(() => page.locator('#mode-chip').textContent().then(t => t.includes('离线')), 5000);

  // FE-KGN-09 Sprint3 含三张 AI 域新卡
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', '3');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '07'));
  check('FE-KGN-09 Sprint3 显示 7 卡且含 M21/M22/M23',
    (await page.locator('#board .card[data-id="M21"], #board .card[data-id="M22"], #board .card[data-id="M23"]').count()) === 3);

  // FE-KGN-01 看板卡血缘徽章(工时加权)
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator('#board .card[data-id="M02"]').count() === 1);
  const badge = (id) => page.locator(`#board .card[data-id="${id}"] .subprog`).textContent();
  check('FE-KGN-01a M02 徽章 ▣ 1/1 · 100%', (await badge('M02')).trim() === '▣ 1/1 · 100%', await badge('M02'));
  check('FE-KGN-01b M22 徽章 ▣ 0/2 · 0%', (await badge('M22')).trim() === '▣ 0/2 · 0%', await badge('M22'));
  check('FE-KGN-01c 无子任务卡(M01)无徽章', (await page.locator('#board .card[data-id="M01"] .subprog').count()) === 0);

  // FE-KGN-02 总览工时加权(精确快照:分子=T03完成16h+无子卡折算1.5=17.5;分母=9卡Σeh 156+无子卡14=170 → 10%)
  // Sprint1=(16+1.5)/(无子卡5+Σeh52)=31%;S2/S3 子任务全未完成 → 0%
  await page.click('.navitem[data-view="overview"]');
  await waitFor(() => page.locator('#ov-percent').textContent().then(t => t.trim() !== ''));
  const pcts = await page.evaluate(() => ({
    total: document.querySelector('#ov-percent').textContent.trim(),
    sprints: [...document.querySelectorAll('#sprint-grid .sprint .pct')].map(x => x.textContent.trim()),
  }));
  check('FE-KGN-02 总览工时加权=10%(非纯计数 9%)', pcts.total === '10%', JSON.stringify(pcts));
  check('FE-KGN-02b Sprint 加权 S1=31%/S2=0%/S3=0%', pcts.sprints.join(',') === '31%,0%,0%', pcts.sprints.join(','));

  // FE-KGN-03 甘特父子层级
  await page.click('.navitem[data-view="gantt"]');
  await waitFor(() => page.locator('.grow.parent').count() === 9);
  const rows = await page.evaluate(() => ({
    parent: document.querySelectorAll('.grow.parent').length,
    child: document.querySelectorAll('.grow.child').length,
    mgmt: document.querySelectorAll('.grow.mgmt').length,
    orphanZone: [...document.querySelectorAll('.grow.mgmt .tname b')].some(b => b.textContent === '独立任务'),
    m02bar: [...document.querySelectorAll('.gbar.parent')].some(b => b.textContent.includes('M02 · 100%')),
    legend: document.querySelector('#gantt-legend').textContent.includes('管理任务'),
  }));
  check('FE-KGN-03 甘特层级 9 父/12 子/4 管理', rows.parent === 9 && rows.child === 12 && rows.mgmt === 4, JSON.stringify(rows));
  check('FE-KGN-03b 父条文本 M02 · 100%(并集+加权)', rows.m02bar);
  check('FE-KGN-03c 默认无独立任务区', !rows.orphanZone);
  check('FE-KGN-03d 图例含管理任务', rows.legend);

  // FE-KGN-05 跨迭代仅 title 提示(844b884:条内不显示 ⚠)
  const t08 = await page.evaluate(() => {
    const b = [...document.querySelectorAll('.gbar')].find(x => x.textContent.trim() === 'T08');
    return b ? { title: b.getAttribute('title'), text: b.textContent } : null;
  });
  check('FE-KGN-05 T08 title 含跨迭代说明且条内无⚠',
    !!t08 && t08.title.includes('跨迭代实施') && !t08.text.includes('⚠'), JSON.stringify(t08));

  // FE-KGN-06 三选一弹窗(取消路径:M11 挂 T07 未完成)
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  onConfirm(page);
  await clickCard(page, 'M11');
  await page.click('#del');
  await page.waitForSelector('#undone-choice[open]');
  const undoneText = (await page.locator('#undone-text').textContent()).trim();
  check('FE-KGN-06a 弹窗文案含卡号与未完成数', undoneText.includes('M11') && undoneText.includes('1 条未完成子任务'), undoneText);
  check('FE-KGN-06b 三选一按钮齐全',
    (await page.locator('#undone-choice [data-undone]').count()) === 3 &&
    (await page.locator('#undone-choice [data-undone="cancel"]').count()) === 1 &&
    (await page.locator('#undone-choice [data-undone="keep"]').count()) === 1 &&
    (await page.locator('#undone-choice [data-undone="detach"]').count()) === 1);
  await page.click('#undone-choice .actions button:not([data-undone])');  // 取消删除
  await waitFor(() => page.locator('#undone-choice:not([open])').count() === 1);
  check('FE-KGN-06c 取消后卡仍在', (await page.locator('#board .card[data-id="M11"]').count()) === 1);
  await page.click('#close').catch(() => {});

  // FE-KGN-08 跨 Sprint 编辑:未完成子任务挪周、已完成保留
  // M08(T05 未完成 W2-2) Sprint1→2:确认后 T05 挪 W4-4
  onConfirm(page);
  await clickCard(page, 'M08');
  await page.selectOption('#form select[name="sprint"]', '2');
  await page.click('#form button[type="submit"]');
  await waitFor(() => page.evaluate(() => document.querySelector('#toast').textContent.includes('M08 已更新')));
  check('FE-KGN-08a 未完成子任务 T05 挪入新 Sprint(W4-4)', JSON.stringify(await taskW(page, 'T05')) === '[4,4]', JSON.stringify(await taskW(page, 'T05')));
  // M02(T03 已完成 W1-2) Sprint1→2:T03 保留原周
  onConfirm(page);
  await clickCard(page, 'M02');
  await page.selectOption('#form select[name="sprint"]', '2');
  await page.click('#form button[type="submit"]');
  await waitFor(() => page.evaluate(() => document.querySelector('#toast').textContent.includes('M02 已更新')));
  check('FE-KGN-08b 已完成子任务 T03 保留原时间轴(W1-2)', JSON.stringify(await taskW(page, 'T03')) === '[1,2]', JSON.stringify(await taskW(page, 'T03')));

  // FE-KGN-04 keep 级联:删 M15(T06 未完成)→解绑进独立任务区
  onConfirm(page);
  await clickCard(page, 'M15');
  await page.click('#del');
  await page.waitForSelector('#undone-choice[open]');
  await page.click('#undone-choice [data-undone="keep"]');
  await waitFor(() => page.locator('#board .card[data-id="M15"]').count() === 0);
  check('FE-KGN-04a keep 删除 M15 后卡消失', true);
  check('FE-KGN-04b T06 解绑(card=null)且状态不变', (await taskCard(page, 'T06')) === null && (await taskStatus(page, 'T06')) === 0);
  await page.click('.navitem[data-view="gantt"]');
  await waitFor(() => page.evaluate(() => [...document.querySelectorAll('.grow.mgmt .tname b')].some(b => b.textContent === '独立任务')));
  const orphanZone = await page.evaluate(() => {
    const zone = [...document.querySelectorAll('.grow.mgmt')].find(r => r.querySelector('b') && r.querySelector('b').textContent === '独立任务');
    if (!zone) return { label: '' };
    const next = zone.nextElementSibling;  // 独立任务区的第一条子行
    return { label: zone.querySelector('small').textContent, firstSub: next && next.classList.contains('child') ? next.querySelector('b').textContent : '' };
  });
  check('FE-KGN-04c 甘特出现独立任务区(1 条)', orphanZone.label.includes('1 条'), JSON.stringify(orphanZone));
  check('FE-KGN-04d 独立区紧跟 T06 子行且子行总数不变(12)',
    orphanZone.firstSub === '看板增强与趋势' && (await page.locator('.grow.child').count()) === 12, orphanZone.firstSub);

  // FE-KGN-07 detach 级联:删 M21(T08 W2-4 进行中、T14 W4-5 待办)
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  onConfirm(page);
  await clickCard(page, 'M21');
  await page.click('#del');
  await page.waitForSelector('#undone-choice[open]');
  await page.click('#undone-choice [data-undone="detach"]');
  await waitFor(() => page.locator('#board .card[data-id="M21"]').count() === 0);
  check('FE-KGN-07a detach 删除 M21 后卡消失', true);
  check('FE-KGN-07b T08 整体挪 2 周(W4-6)', JSON.stringify(await taskW(page, 'T08')) === '[4,6]', JSON.stringify(await taskW(page, 'T08')));
  check('FE-KGN-07c T14 钳制到 W6-6 不倒挂', JSON.stringify(await taskW(page, 'T14')) === '[6,6]', JSON.stringify(await taskW(page, 'T14')));
  check('FE-KGN-07d 两条均解绑', (await taskCard(page, 'T08')) === null && (await taskCard(page, 'T14')) === null);

  // 收尾:恢复演示回 23 张卡
  onConfirm(page);
  await page.click('#reset');
  await waitFor(() => page.locator('#board .card[data-id="M21"]').count() === 1);
  check('FE-KGN-10 恢复演示后 M21 回位(23 卡)', (await page.evaluate(() => stories.length)) === 23);
  check('FE-KGN-11 离线全程无 JS 异常', jsErr.length === 0, jsErr.join(' | '));
  await ctx.close();
}

async function apiCall(page, base, method, path, body) {
  return page.evaluate(async ({ b, m, p, d }) => {
    const r = await fetch(b + p, { method: m, headers: { Authorization: 'Bearer ' + localStorage.getItem('aiguanli_token'), 'Content-Type': 'application/json' }, body: d ? JSON.stringify(d) : undefined });
    return { status: r.status, body: await r.json().catch(() => null) };
  }, { b: base, m: method, p: path, d: body || null });
}

async function suiteOnline(browser) {
  console.log('\n== 血缘 · 在线联调(OE-KGN,全部可逆)==');
  const { ctx, page } = await openApp(browser, QA);
  const jsErr = []; page.on('pageerror', e => jsErr.push(e.message));
  await waitFor(() => page.locator('#login').isVisible().catch(() => false), 7000);
  await page.fill('#login-user', '成员1');
  await page.fill('#login-pass', '123456');
  await page.click('#login-form button[type="submit"]');
  await waitFor(() => page.locator('#user-chip').textContent().then(t => t.includes('成员1')), 6000);

  // OE-KGN-03 在线渲染与离线一致
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator('#board .card[data-id="M02"]').count() === 1);
  const b = (await page.locator('#board .card[data-id="M02"] .subprog').textContent()).trim();
  check('OE-KGN-03a 在线 M02 徽章 ▣ 1/1 · 100%', b === '▣ 1/1 · 100%', b);
  await page.click('.navitem[data-view="gantt"]');
  await waitFor(() => page.locator('.grow.parent').count() === 9);
  check('OE-KGN-03b 在线甘特 9 父/12 子/4 管理',
    (await page.locator('.grow.parent').count()) === 9 && (await page.locator('.grow.child').count()) === 12 && (await page.locator('.grow.mgmt').count()) === 4);

  // OE-KGN-01 在线删卡 cancel 级联(造临时卡 M24 挂 T06,测后恢复)
  const created = await apiCall(page, QA, 'POST', '/api/stories', { title: '血缘级联在线测试卡', sprint: 1, activity: 2, status: 0 });
  const cardId = created.body.id;
  check('OE-KGN-01a API 造临时卡(' + cardId + ')', created.status === 200 && !!cardId);
  await apiCall(page, QA, 'PATCH', '/api/tasks/T06', { kanban_card_id: cardId });
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitFor(() => page.locator('#user-chip').textContent().then(t => t.includes('成员1')), 6000);
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator(`#board .card[data-id="${cardId}"]`).count() === 1);
  onConfirm(page);
  await clickCard(page, cardId);
  await page.click('#del');
  await page.waitForSelector('#undone-choice[open]');
  await page.click('#undone-choice [data-undone="cancel"]');
  const cascaded = await waitFor(() => page.locator('.toast').textContent().then(t => t.includes('子任务级联:标记已取消')), 7000);
  check('OE-KGN-01b UI 走 ?undone=cancel 并提示级联', cascaded);
  const t06 = (await apiCall(page, QA, 'GET', '/api/tasks')).body.find(t => t.id === 'T06');
  check('OE-KGN-01c 服务端 T06 status=3 已解绑', t06.status === 3 && t06.kanban_card_id === null, JSON.stringify(t06));
  // 恢复:T06 挂回 M15 置待办
  await apiCall(page, QA, 'PATCH', '/api/tasks/T06', { kanban_card_id: 'M15', status: 0 });
  const t06r = (await apiCall(page, QA, 'GET', '/api/tasks')).body.find(t => t.id === 'T06');
  check('OE-KGN-01d 恢复 T06 挂 M15 待办', t06r.status === 0 && t06r.kanban_card_id === 'M15');

  // OE-KGN-02 在线跨 Sprint 挪动(M08 Sprint1→2 → T05 挪 W4-4;测后恢复)
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitFor(() => page.locator('#user-chip').textContent().then(t => t.includes('成员1')), 6000);
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator('#board .card[data-id="M08"]').count() === 1);
  onConfirm(page);
  await clickCard(page, 'M08');
  await page.selectOption('#form select[name="sprint"]', '2');
  await page.click('#form button[type="submit"]');
  await waitFor(() => page.locator('.toast').textContent().then(t => t.includes('已同步到服务器')), 7000);
  const t05 = (await apiCall(page, QA, 'GET', '/api/tasks')).body.find(t => t.id === 'T05');
  check('OE-KGN-02a 服务端 T05 已挪 W4-4', t05.week_start === 4 && t05.week_end === 4, JSON.stringify(t05));
  // 恢复:M08 回 Sprint1,T05 回 W2-2
  await apiCall(page, QA, 'PATCH', '/api/stories/M08', { sprint: 1 });
  await apiCall(page, QA, 'PATCH', '/api/tasks/T05', { week_start: 2, week_end: 2 });
  const t05r = (await apiCall(page, QA, 'GET', '/api/tasks')).body.find(t => t.id === 'T05');
  const m08r = (await apiCall(page, QA, 'GET', '/api/stories')).body.find(s => s.id === 'M08');
  check('OE-KGN-02b 恢复 M08 S1 / T05 W2-2', m08r.sprint === 1 && t05r.week_start === 2 && t05r.week_end === 2);
  const final = await apiCall(page, QA, 'GET', '/api/stories');
  check('OE-KGN-02c QA 库恢复 23 卡(无残留)', final.body.length === 23, 'got ' + final.body.length);
  check('OE-KGN-03c 在线全程无 JS 异常', jsErr.length === 0, jsErr.join(' | '));
  await ctx.close();
}

(async () => {
  let browser = null;
  try { browser = await chromium.launch({ channel: 'msedge', headless: true }); }
  catch (e) { browser = await chromium.launch({ headless: true }); }
  console.log('浏览器已启动(' + (browser.browserType().name() || 'chromium') + ')');
  try { await suiteOffline(browser); } catch (e) { failed++; results.push({ name: 'suiteOffline', ok: false, extra: e.message }); console.log('  ✘ suiteOffline 异常:', e.message); }
  try { await suiteOnline(browser); } catch (e) { failed++; results.push({ name: 'suiteOnline', ok: false, extra: e.message }); console.log('  ✘ suiteOnline 异常:', e.message); }
  await browser.close();
  console.log(`\n===== KGN E2E 结果: ${passed} passed / ${failed} failed =====`);
  for (const r of results.filter(x => !x.ok)) console.log('  FAIL:', r.name, '::', r.extra || '');
  process.exit(failed ? 1 : 0);
})();
