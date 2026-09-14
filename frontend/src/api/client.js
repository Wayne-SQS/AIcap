/* fetch 封装:行为 1:1 对齐旧版 index.html api()(L1266-1274)
   baseURL 三级解析:window.__AICAP_API_BASE__(E2E / addInitScript 注入点) > VITE_API_BASE > 默认
   —— index.html 已不再硬编码该全局(见该文件注释,ENV-D02),故 VITE_API_BASE 现在真正生效
   默认指向现行 Spring Boot 后端(java-backend/,8080);FastAPI 版(8000)已归档至 backend/ */
import { TOKEN_KEY } from '@/constants'

export function apiBase() {
  return window.__AICAP_API_BASE__ || import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8080'
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

/** 登录接口:它的 401 是「凭据错误」而不是「会话过期」,不能走全局过期处理 */
function isLoginPath(path) {
  return String(path).split('?')[0].replace(/\/+$/, '') === '/api/auth/login'
}

export async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {})
  const token = getAuthToken()
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetch(apiBase() + path, Object.assign({}, opts, { headers }))
  if (res.status === 401) {
    /* 先解析响应体拿后端真实原因(登录失败时是「用户名或密码错误」),解析不到才退回「未登录」 */
    let msg = '未登录'
    try {
      const j = await res.json()
      if (typeof j.detail === 'string' && j.detail.trim()) msg = j.detail
    } catch (e) { /* 非 JSON 响应:保持默认文案 */ }
    /* 登录接口的 401 只提示原因,不触发「登录已过期」的全局处理 */
    if (!isLoginPath(path) && unauthorizedHandler) unauthorizedHandler()
    throw new Error(msg)
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
