/* fetch 封装:行为 1:1 对齐旧版 index.html api()(L1266-1274)
   baseURL 三级解析:window.__AICAP_API_BASE__(E2E 字符串替换锚点) > VITE_API_BASE > 默认
   未来切换 Spring Boot 后端只需替换锚点值或环境变量,本文件零改动 */
import { TOKEN_KEY } from '@/constants'

export function apiBase() {
  return window.__AICAP_API_BASE__ || import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8000'
}

export function getAuthToken() {
  return localStorage.getItem(TOKEN_KEY) || null
}

export function setAuthToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/* 401 处理注入:由 main.js 绑定 session store,避免 client↔store 循环依赖 */
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) { unauthorizedHandler = fn }

export async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {})
  const token = getAuthToken()
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetch(apiBase() + path, Object.assign({}, opts, { headers }))
  if (res.status === 401) {
    if (unauthorizedHandler) unauthorizedHandler()
    throw new Error('未登录')
  }
  if (!res.ok) {
    let msg = '请求失败 (' + res.status + ')'
    try { const j = await res.json(); msg = (typeof j.detail === 'string') ? j.detail : msg } catch (e) { /* ignore */ }
    throw new Error(msg)
  }
  if (res.status === 204) return null
  return res.json()
}

/* 健康检查:2.5s 超时,对齐旧版 bootstrap()(L1462-1468) */
export async function health() {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 2500)
  try {
    const r = await fetch(apiBase() + '/api/health', { signal: ctrl.signal })
    clearTimeout(t)
    return r.ok
  } catch (e) {
    clearTimeout(t)
    return false
  }
}
