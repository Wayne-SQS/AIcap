/* ==========================================================================
 * 爱管理 E2E 双模式公共助手
 * --------------------------------------------------------------------------
 * AICAP_UI_FLAVOR=legacy(默认)  读仓库根 index.html,锚点 const API_BASE='...'
 * AICAP_UI_FLAVOR=vue            读 frontend/dist/index.html,锚点 window.__AICAP_API_BASE__='...'
 *
 * vue 模式运行前提:cd frontend && npm run build,且静态服务器指向 frontend/dist
 * (默认端口 8092,可用 AICAP_UI_URL 覆盖;legacy 默认 8090 指向仓库根)。
 * vue 为 SPA,首屏需加载模块脚本,waitFor 默认超时放宽 2.5 倍(6s→15s)。
 * ========================================================================== */
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '..');
const FLAVOR = process.env.AICAP_UI_FLAVOR || 'legacy';

const LEGACY_ANCHOR = "const API_BASE='http://127.0.0.1:8000';";
const VUE_ANCHOR = "window.__AICAP_API_BASE__='http://127.0.0.1:8000';";

function flavor() { return FLAVOR; }
function isVue() { return FLAVOR === 'vue'; }

/** 默认页面地址:legacy=8090(仓库根静态服务),vue=8092(frontend/dist 静态服务) */
function defaultPageUrl() {
  if (isVue()) return process.env.AICAP_UI_URL || 'http://127.0.0.1:8092/index.html';
  return process.env.AICAP_UI_URL || 'http://127.0.0.1:8090/index.html';
}

/** 读取当前 flavor 的 HTML 并把 API 锚点替换为 apiBase(不改原文件) */
function flavorHtml(apiBase) {
  const file = isVue()
    ? path.join(REPO, 'frontend', 'dist', 'index.html')
    : path.join(REPO, 'index.html');
  return fs.readFileSync(file, 'utf8')
    .replace(LEGACY_ANCHOR, `const API_BASE='${apiBase}';`)
    .replace(VUE_ANCHOR, `window.__AICAP_API_BASE__='${apiBase}';`);
}

/** vue flavor 下等待超时倍率(6s→15s) */
function waitScale() { return isVue() ? 2.5 : 1; }

/** 打开应用:route 拦截 index.html 注入替换后的 HTML(各 spec 可传 ctxOpts/newPage 钩子) */
async function openApp(browser, apiBase, opts = {}) {
  const { pageUrl, ctxOpts = {}, onNewPage } = opts;
  const ctx = await browser.newContext(ctxOpts);
  await ctx.grantPermissions(['local-network-access']);
  const page = await ctx.newPage();
  if (onNewPage) onNewPage(page);
  await page.route('**/index.html', r => r.fulfill({ contentType: 'text/html', body: flavorHtml(apiBase) }));
  await page.goto(pageUrl || defaultPageUrl(), { waitUntil: 'domcontentloaded' });
  return { ctx, page };
}

module.exports = { REPO, flavor, isVue, defaultPageUrl, flavorHtml, waitScale, openApp };
