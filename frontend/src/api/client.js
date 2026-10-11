/* fetch 封装:行为 1:1 对齐旧版 index.html api()(L1266-1274)
   baseURL 三级解析:window.__AICAP_API_BASE__(E2E / addInitScript 注入点) > VITE_API_BASE > 默认
   —— index.html 已不再硬编码该全局(见该文件注释,ENV-D02),故 VITE_API_BASE 现在真正生效
   开发环境默认指向Spring Boot 8080；生产构建默认使用同源/api反向代理。 */
import { TOKEN_KEY } from '@/constants'

export function apiBase() {
  if (window.__AICAP_API_BASE__) return window.__AICAP_API_BASE__
  if (import.meta.env.VITE_API_BASE) return import.meta.env.VITE_API_BASE
  return import.meta.env.PROD ? '' : 'http://127.0.0.1:8080'
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

/* 默认超时:后端半死时不能让请求永久 pending —— 那会让界面停在 loading,
   既不进演示模式也不报错。同步长任务的端点显式传 timeoutMs(0 = 不限时)。 */
export const DEFAULT_TIMEOUT_MS = 30000
/* 同步长任务(LLM agent / 上传解析 / 多个外部 API 串行)的推荐值 */
export const LONG_TIMEOUT_MS = 300000

export async function api(path, opts = {}, base = apiBase()) {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, ...rest } = opts
  const headers = Object.assign({}, rest.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }, rest.headers || {})
  const token = getAuthToken()
  if (token) headers['Authorization'] = 'Bearer ' + token
  const ctrl = new AbortController()
  const timer = timeoutMs > 0 ? setTimeout(() => ctrl.abort(), timeoutMs) : null
  try {
    const res = await fetch(base + path, Object.assign({}, rest, { headers, signal: rest.signal || ctrl.signal }))
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
      const error = new Error(msg)
      error.status = res.status
      throw error
    }
    if (res.status === 204) return null
    /* await 放在 try 内:超时同样覆盖响应体读取(否则只覆盖到响应头就提前 clearTimeout) */
    return await res.json()
  } catch (e) {
    if (e && e.name === 'AbortError' && ctrl.signal.aborted) {
      const error = new Error('请求超时,请稍后重试')
      error.status = 0
      error.timeout = true
      throw error
    }
    throw e
  } finally {
    if (timer) clearTimeout(timer)
  }
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
