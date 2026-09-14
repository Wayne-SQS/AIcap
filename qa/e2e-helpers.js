/* ==========================================================================
 * 爱管理 E2E 双模式公共助手
 * --------------------------------------------------------------------------
 * AICAP_UI_FLAVOR=legacy(默认)  读 legacy/index.html(Vue 改造前的旧版前端,归档保留),锚点 const API_BASE='...'
 * AICAP_UI_FLAVOR=vue            读 frontend/dist/index.html(Vue3 现行前端),在 <head> 注入
 *                                window.__AICAP_API_BASE__='<apiBase>' 脚本(不依赖源文件锚点)
 *
 * vue 模式运行前提:cd frontend && npm run build,且静态服务器指向 frontend/dist
 * (默认端口 8092,可用 AICAP_UI_URL 覆盖;legacy 默认 8090,静态服务器仍指向仓库根,页面在 /legacy/)。
 * vue 为 SPA,首屏需加载模块脚本,waitFor 默认超时放宽 2.5 倍(6s→15s)。
 * ========================================================================== */
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '..');
const FLAVOR = process.env.AICAP_UI_FLAVOR || 'legacy';

const LEGACY_ANCHOR = "const API_BASE='http://127.0.0.1:8000';";

/** vue 模式:ENV-D02 修复后 frontend/index.html 已不再硬编码 __AICAP_API_BASE__,
 *  原先的字符串替换锚点会静默失效。改为在 </head> 前注入一段设置该全局的脚本 ——
 *  不依赖源文件里的任何锚点字符串,前端 HTML 怎么改都不会让注入失效。 */
function injectVueBase(html, apiBase) {
  const tag = `<script>window.__AICAP_API_BASE__='${apiBase}';</script>`
  return html.includes(tag) ? html : html.replace('</head>', `${tag}\n</head>`)
}

function flavor() { return FLAVOR; }
function isVue() { return FLAVOR === 'vue'; }

/** 默认页面地址:legacy=8090/legacy/(仓库根静态服务,旧版页面已归档),vue=8092(frontend/dist 静态服务) */
function defaultPageUrl() {
  if (isVue()) return process.env.AICAP_UI_URL || 'http://127.0.0.1:8092/index.html';
  return process.env.AICAP_UI_URL || 'http://127.0.0.1:8090/legacy/index.html';
}

/** 读取当前 flavor 的 HTML 并把 API 锚点/全局设为 apiBase(不改原文件) */
function flavorHtml(apiBase) {
  const file = isVue()
    ? path.join(REPO, 'frontend', 'dist', 'index.html')
    : path.join(REPO, 'legacy', 'index.html');
  const html = fs.readFileSync(file, 'utf8')
    .replace(LEGACY_ANCHOR, `const API_BASE='${apiBase}';`);
  return isVue() ? injectVueBase(html, apiBase) : html;
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
