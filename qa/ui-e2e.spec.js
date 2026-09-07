/**
 * 爱管理 · 前端 UI 端到端测试(Playwright)
 * =======================================
 * 运行前提:
 *  1) 后端 QA 实例:uvicorn 于 127.0.0.1:8001,连接 AIcap_qa(先跑 reset_qa_db.py)
 *  2) 静态服务器:仓库根目录 python -m http.server 8090
 *  3) playwright-core + chromium 已安装
 *
 * 模式:
 *  - OFFLINE:API_BASE 指到无人监听的端口 → 页面进入"离线演示模式",覆盖 FE 用例
 *  - ONLINE :API_BASE 指到 8001 QA 后端 → 覆盖 OE 联调用例与双端一致性
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

/** 把 index.html 中的 API_BASE 替换为目标地址后交给浏览器(不改原文件) */
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

/** 模拟 HTML5 拖拽:把 data-id 的卡片拖到 data-status 的列(使用同一 DataTransfer) */
async function dragCard(page, storyId, toStatus) {
  return page.evaluate(({ id, st }) => {
    const card = document.querySelector(`#board .card[data-id="${id}"]`);
    const col = document.querySelector(`#board .column[data-status="${st}"]`);
    if (!card || !col) return false;
    const dt = new DataTransfer();
    card.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }));
    col.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }));
    col.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
    document.dispatchEvent(new DragEvent('dragend', { bubbles: true, dataTransfer: dt }));
    return true;
  }, { id: storyId, st: String(toStatus) });
}

async function clickCard(page, storyId) { await page.click(`#board .card[data-id="${storyId}"]`); await page.waitForSelector('#editor[open]'); }

async function newStory(page, title, description = '作为QA，我希望跑通该流程', acceptance = '流程成功') {
  await page.click('#new');
  await page.fill('#form input[name="title"]', title);
  await page.fill('#form textarea[name="description"]', description);
  await page.fill('#form textarea[name="acceptance"]', acceptance);
  await page.click('#form button[type="submit"]');
}

function onConfirm(page) { page.once('dialog', d => d.accept()); }
function onPrompt(page, val = '测试理由') { page.once('dialog', d => d.accept(val)); }

async function suiteOffline(browser) {
  console.log('\n== 前端离线演示模式(FE)==');
  const { ctx, page } = await openApp(browser, DEAD);
  const jsErr = []; page.on('pageerror', e => jsErr.push(e.message));
  const ok = await waitFor(() => page.locator('#mode-chip').textContent().then(t => t.includes('离线')), 5000);
  check('FE-01 后端不可达时进入离线演示模式', ok && (await page.textContent('#mode-chip')).includes('离线'));
  check('FE-02 页面无 JS 异常', jsErr.length === 0, jsErr.join(' | '));

  await page.click('.navitem[data-view="overview"]');
  await waitFor(() => page.locator('#ov-total').textContent().then(t => t.trim() !== ''));
  check('FE-03 总览故事总数=23', (await page.textContent('#ov-total')).trim() === '23');
  check('FE-04 总览规划任务=16', (await page.textContent('#ov-tasks')).trim() === '16');

  await page.click('.navitem[data-view="board"]');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '09'), 4000);
  check('FE-05 看板默认过滤 Sprint1 显示 9 条', (await page.textContent('#total')).trim() === '09');
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '23'));
  check('FE-06 全部Sprint显示 23 条', (await page.textContent('#total')).trim() === '23');
  check('FE-07 看板三列存在', (await page.locator('.column').count()) === 3);
  const colCounts = await page.evaluate(() => [...document.querySelectorAll('.column')].map(x => x.querySelector('.count').textContent));
  check('FE-08 列计数=待办18/进行中3/完成2', colCounts.join(',') === '18,3,2', colCounts.join(','));

  await page.selectOption('#sprint', '1');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '09'));
  check('FE-09 Sprint1 过滤 9 条', (await page.locator('#board .card').count()) === 9);
  await page.fill('#search', '登录');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '01'));
  check('FE-10 搜索"登录"仅剩 M01', (await page.locator('#board .card').count()) === 1 && (await page.locator('#board .card').first().getAttribute('data-id')) === 'M01');
  await page.fill('#search', '');
  await page.selectOption('#sprint', 'all');
  await page.selectOption('#owner', '0');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '05'));
  check('FE-11 负责人=成员1 过滤 5 条', (await page.locator('#board .card').count()) === 5);
  await page.selectOption('#owner', 'all');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '20'));

  await page.click('#maptab');
  await waitFor(() => page.locator('#mappanel:not([hidden])').count() === 1);
  check('FE-12 故事地图面板可切换显示', (await page.locator('#map .mapcell').count()) > 0);
  await page.click('#boardtab');

  // 新建(当前过滤全部,默认落入 Sprint1;M21-M23 已被 AI 域卡占用,新卡编号为 M24)
  await newStory(page, 'E2E新建故事');
  await waitFor(() => page.locator('#board .card[data-id="M24"]').count() === 1);
  check('FE-13 新建故事生成 M24', (await page.locator('#board .card[data-id="M24"]').count()) === 1);
  check('FE-14 新建后总数=24', (await page.textContent('#total')).trim() === '24');

  // 编辑
  await clickCard(page, 'M24');
  await page.fill('#form input[name="title"]', 'E2E改名故事');
  await page.click('#form button[type="submit"]');
  await waitFor(() => page.locator('#board .card[data-id="M24"] h3').textContent().then(t => t === 'E2E改名故事'));
  check('FE-15 编辑故事标题生效', true);

  // 拖拽 M05(待办)到"已完成"
  await dragCard(page, 'M05', 2);
  const moved = await waitFor(() => page.evaluate(() => {
    const col = [...document.querySelectorAll('#board .column')].find(x => x.dataset.status === '2');
    return col && [...col.querySelectorAll('.card')].some(c => c.dataset.id === 'M05');
  }));
  check('FE-16 拖拽后 M05 进入已完成列', moved);

  // 变更记录
  check('FE-17 变更记录有内容', (await page.textContent('#logbody')).length > 0);

  // 删除(新卡 M24 无子任务,confirm 后直接删,不触发三选一弹窗)
  onConfirm(page);
  await clickCard(page, 'M24');
  await page.click('#del');
  await waitFor(() => page.locator('#board .card[data-id="M24"]').count() === 0);
  check('FE-18 删除 M24 生效', true);

  // 需求池
  await page.click('.navitem[data-view="pool"]');
  await waitFor(() => page.locator('.pool-item').count() === 4);
  check('FE-19 需求池默认 4 条', (await page.locator('.pool-item').count()) === 4);
  await page.click('#pool-add');
  await page.fill('#pool-title', 'E2E池子需求');
  await page.click('#pool-save');
  await waitFor(() => page.locator('.pool-item').count() === 5);
  check('FE-20 需求池新增至 5 条', true);
  await page.click('.pool-item .pacts [data-promote="R01"]');
  await page.selectOption('dialog[open] [name=sprint]', '1');
  await page.click('dialog[open] button[type=submit]');
  await waitFor(() => page.locator('.pool-item').count() === 4);
  check('FE-21 R01 移入看板后池剩 4 条', true);
  await page.click('.pool-item .pacts [data-drop="R02"]');
  await waitFor(() => page.locator('.pool-item').count() === 3);
  check('FE-22 移除池条目生效', true);

  // AI 审核中心
  await page.click('.navitem[data-view="review"]');
  await waitFor(() => page.locator('.sug-card').count() === 4);
  check('FE-23 AI审核中心默认 4 条建议', (await page.locator('.sug-card').count()) === 4);
  onPrompt(page);
  await page.locator('.sug-card').first().locator('button[data-act="approved"]').click();
  await waitFor(() => page.locator('.sug-card').first().locator('.sug-status').textContent().then(t => t === '已采纳'));
  check('FE-24 采纳建议状态变已采纳', true);
  check('FE-25 决策留痕已记录', (await page.textContent('#decision-log')).includes('采纳'));

  // AI 助手(对话)
  await page.click('.navitem[data-view="ai"]');
  await waitFor(() => page.locator('.aicard').count() === 6);
  check('FE-26 AI 六项能力卡片渲染', (await page.locator('.aicard').count()) === 6);
  await page.fill('#chatinput', '帮我拆解一个用户故事');
  await page.click('#chatsend');
  await waitFor(() => page.locator('.msg.bot').count() >= 3, 8000);
  check('FE-27 对话演示有机器人回复', (await page.locator('.msg.bot').count()) >= 3);

  // 甘特图(血缘重构后:9 父行[看板卡] + 12 子行[开发任务] + 4 管理行 = 25 行)
  await page.click('.navitem[data-view="gantt"]');
  await waitFor(() => page.locator('.grow').count() === 25);
  check('FE-28 甘特图渲染 25 行(9父+12子+4管理)', true);
  check('FE-29 甘特图含 4 个里程碑', (await page.locator('.mlabel').count()) === 4);

  // 成员任务图
  await page.click('.navitem[data-view="members"]');
  await waitFor(() => page.locator('#member-grid .mcard').count() === 4);
  check('FE-30 成员任务图渲染 4 成员卡', true);
  check('FE-31 热力图 4 行', (await page.locator('.heat-row').count()) === 4);

  // UML
  await page.click('.navitem[data-view="uml"]');
  await waitFor(() => page.locator('.uc').count() === 9);
  check('FE-32 UML 用例图含 9 用例', true);

  // 导出
  await page.click('.navitem[data-view="board"]');
  await waitFor(() => page.locator('#expjson').count() === 1);
  const [dl1] = await Promise.all([page.waitForEvent('download', { timeout: 5000 }).catch(() => null), page.click('#expjson')]);
  check('FE-33 导出 JSON 触发下载', !!dl1 && dl1.suggestedFilename().startsWith('爱管理-用户故事') && dl1.suggestedFilename().endsWith('.json'), dl1 ? dl1.suggestedFilename() : 'no download');
  const [dl2] = await Promise.all([page.waitForEvent('download', { timeout: 5000 }).catch(() => null), page.click('#expcsv')]);
  check('FE-34 导出 CSV 触发下载', !!dl2 && dl2.suggestedFilename().endsWith('.csv'), dl2 ? dl2.suggestedFilename() : 'no download');

  // 恢复演示
  onConfirm(page);
  await page.click('#reset');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '23'));
  check('FE-35 恢复演示回 23 条且无 M24', (await page.locator('#board .card[data-id="M24"]').count()) === 0);

  await ctx.close();
}

async function suiteOnline(browser) {
  console.log('\n== 前端在线模式 + 前后端联调(OE)==');
  // ---- 会话1:管理员 成员1 ----
  const { ctx, page } = await openApp(browser, QA);
  const jsErr = []; page.on('pageerror', e => jsErr.push(e.message));
  await waitFor(() => page.locator('#login').isVisible().catch(() => false), 7000);
  check('OE-01 后端可达时弹登录框', await page.locator('#login').isVisible().catch(() => false));
  await page.fill('#login-user', '成员1');
  await page.fill('#login-pass', '123456');
  await page.click('#login-form button[type="submit"]');
  await waitFor(() => page.locator('#user-chip').textContent().then(t => t.includes('成员1')), 6000);
  check('OE-02 登录成员1(管理员)成功', (await page.textContent('#user-chip')).includes('成员1'));
  check('OE-03 状态芯片显示在线已连接', (await page.textContent('#mode-chip')).includes('在线 · 已连接后端'));
  await waitFor(() => page.locator('#hintcount').textContent().then(t => t.includes('已同步 23 条故事')), 6000);
  check('OE-04 登录后同步服务器 23 条故事', true);
  check('OE-05 在线模式无 JS 异常', jsErr.length === 0, jsErr.join(' | '));

  // 服务端数据与渲染一致
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '23'));
  const apiCount = await page.evaluate(async (base) => {
    const r = await fetch(base + '/api/stories', { headers: { Authorization: 'Bearer ' + localStorage.getItem('aiguanli_token') } });
    return (await r.json()).length;
  }, QA);
  check('OE-06 渲染总数与服务端一致(23)', apiCount === 23 && (await page.textContent('#total')).trim() === '23', 'api=' + apiCount);

  // 在线新建 → 服务端可见
  await newStory(page, '在线E2E故事');
  await waitFor(() => page.locator('.toast').textContent().then(t => t.includes('已同步到服务器')), 7000);
  const created = await page.evaluate(async (base) => {
    const h = { Authorization: 'Bearer ' + localStorage.getItem('aiguanli_token') };
    const list = await (await fetch(base + '/api/stories', { headers: h })).json();
    const s = list.find(x => x.title === '在线E2E故事');
    return s ? { id: s.id, count: list.length } : null;
  }, QA);
  check('OE-07 在线新建落库可查(' + (created ? created.id : '?') + ')', !!created && created.count === 24, JSON.stringify(created));

  // 在线拖拽 → PATCH → 服务端状态改变
  await page.selectOption('#sprint', 'all');
  await page.selectOption('#owner', 'all');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '24'));
  const dragOk = await dragCard(page, 'M05', 2);
  const serverDone = await waitFor(() => page.evaluate(async (base) => {
    const h = { Authorization: 'Bearer ' + localStorage.getItem('aiguanli_token') };
    const list = await (await fetch(base + '/api/stories', { headers: h })).json();
    return (list.find(x => x.id === 'M05') || {}).status === 2;
  }, QA), 7000);
  check('OE-08 拖拽 M05 后服务端 status=2', dragOk && serverDone);

  // 刷新(相当于另一浏览器/另一用户视角)数据一致 —— 并回归验证 BUG-FE-01:刷新后登录态 UI 应保持"已连接"
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitFor(() => page.evaluate(() => document.querySelector('#hintcount').textContent.includes('已同步 24')), 7000);
  await page.click('.navitem[data-view="board"]');
  await page.selectOption('#sprint', 'all');
  await page.selectOption('#owner', 'all');
  await waitFor(() => page.locator('#total').textContent().then(t => t.trim() === '24'));
  const afterReload = await page.evaluate(() => {
    const col = [...document.querySelectorAll('#board .column')].find(x => x.dataset.status === '2');
    return col && [...col.querySelectorAll('.card')].some(c => c.dataset.id === 'M05');
  });
  check('OE-09 刷新后 M05 仍在已完成(共享库一致)', afterReload);
  const chipAfterReload = (await page.textContent('#mode-chip')) || '';
  const userChipVisible = await page.locator('#user-chip').isVisible().catch(() => false);
  const logoutVisible = await page.locator('#logout-btn').isVisible().catch(() => false);
  // 回归:BUG-FE-01 修复后,刷新应仍显示"在线 · 已连接后端"且退出按钮可见
  check('OE-12 刷新后状态栏仍显示已连接(BUG-FE-01 回归)', chipAfterReload.includes('在线 · 已连接后端') && userChipVisible && logoutVisible, 'chip="' + chipAfterReload + '" userChipVisible=' + userChipVisible + ' logoutVisible=' + logoutVisible);
  await ctx.close();

  // ---- 会话2:普通成员 成员3(越权删除校验)----
  const { ctx: ctx3, page: page3 } = await openApp(browser, QA);
  await waitFor(() => page3.locator('#login').isVisible().catch(() => false), 7000);
  await page3.fill('#login-user', '成员3');
  await page3.fill('#login-pass', '123456');
  await page3.click('#login-form button[type="submit"]');
  await waitFor(() => page3.locator('#user-chip').textContent().then(t => t.includes('成员3')), 6000);
  await page3.click('.navitem[data-view="board"]');
  await page3.selectOption('#sprint', 'all');
  await waitFor(() => page3.locator('#board .card[data-id="M01"]').count() === 1, 5000);
  const beforeCount = (await page3.textContent('#total')).trim();
  onConfirm(page3);
  // 用 M01(无子任务)验证越权:M03 挂有子任务 T09,删除会先弹三选一弹窗,该场景由 kanban-gantt-e2e 覆盖
  await clickCard(page3, 'M01');
  await page3.click('#del');
  const denied = await waitFor(() => page3.locator('.toast').textContent().then(t => t.includes('无权限')), 6000);
  check('OE-10 成员3 删除被服务端拒绝并提示', denied);
  await page3.waitForSelector('#editor:not([open])', { timeout: 6000 }).catch(() => {});
  await page3.click('#close').catch(() => {});
  check('OE-11 拒绝后 M01 仍在', (await page3.locator('#board .card[data-id="M01"]').count()) === 1);
  await ctx3.close();
}

async function suiteCleanup(browser) {
  console.log('\n== 在线模式数据清理(OE-C)==');
  const { ctx, page } = await openApp(browser, QA);
  await waitFor(() => page.locator('#login').isVisible().catch(() => false), 6000);
  await page.fill('#login-user', '成员1');
  await page.fill('#login-pass', '123456');
  await page.click('#login-form button[type="submit"]');
  await waitFor(() => page.locator('#user-chip').textContent().then(t => t.includes('成员1')), 6000);
  const cleaned = await page.evaluate(async (base) => {
    const h = { Authorization: 'Bearer ' + localStorage.getItem('aiguanli_token'), 'Content-Type': 'application/json' };
    const list = await (await fetch(base + '/api/stories', { headers: h })).json();
    const created = list.filter(x => x.title === '在线E2E故事');
    let del = 0;
    for (const s of created) { const r = await fetch(base + '/api/stories/' + s.id, { method: 'DELETE', headers: h }); if (r.ok) del++; }
    const m05 = list.find(x => x.id === 'M05');
    if (m05 && m05.status === 2) { await fetch(base + '/api/stories/M05', { method: 'PATCH', headers: h, body: JSON.stringify({ status: 0 }) }); }
    return { del, left: (await (await fetch(base + '/api/stories', { headers: h })).json()).length };
  }, QA);
  check('OE-C1 清理 E2E 新建故事且总数回 23', cleaned.del === 1 && cleaned.left === 23, JSON.stringify(cleaned));
  await ctx.close();
}

(async () => {
  // 优先系统已装 Edge(Playwright channel msedge),避免下载 Chromium
  let browser = null;
  try { browser = await chromium.launch({ channel: 'msedge', headless: true }); }
  catch (e) { browser = await chromium.launch({ headless: true }); }
  console.log('浏览器已启动(' + (browser.browserType().name() || 'chromium') + ')');
  try { await suiteOffline(browser); } catch (e) { failed++; results.push({ name: 'suiteOffline', ok: false, extra: e.message }); console.log('  ✘ suiteOffline 异常:', e.message); }
  try { await suiteOnline(browser); } catch (e) { failed++; results.push({ name: 'suiteOnline', ok: false, extra: e.message }); console.log('  ✘ suiteOnline 异常:', e.message); }
  try { await suiteCleanup(browser); } catch (e) { console.log('  清理异常(可忽略):', e.message); }
  await browser.close();
  console.log(`\n===== UI E2E 结果: ${passed} passed / ${failed} failed =====`);
  for (const r of results.filter(x => !x.ok)) console.log('  FAIL:', r.name, '::', r.extra || '');
  process.exit(failed ? 1 : 0);
})();
