/* 主管智能体(「本」)前端接口封装
   ---------------------------------------------------------------
   两件事:
   1) POST /chat        —— 起一轮对话,立刻回 {session_id, run_id}(循环活在请求之外)
   2) GET  /chat/stream —— 只读 SSE 事件通道(重放 + 增量 + 心跳)

   base 解析三级(与 api/client.js 的 apiBase 同构,方便 E2E 注入):
     window.__AICAP_ORCH_BASE__ > VITE_ORCH_BASE > 开发默认 127.0.0.1:8100 / 生产同源

   注意:编排层**不是** 8080 那个 Java 平台,它是独立的 Python 服务(8100)。
   事件真相在 Java 的 agent_events 里,编排层只是搬运工 —— 所以断线重连用
   Last-Event-ID 续传能拿到完整历史,这一点前端可以直接依赖。 */
import { getAuthToken } from './client'

/* SSE 靠 fetch + ReadableStream 手工解析,不用原生 EventSource —— 因为 EventSource
   **不能带自定义请求头**,而本服务鉴权是 `Authorization: Bearer`(见 app/main.py#_bearer),
   身份不允许退化成查询参数(契约 §2.5:身份只来自 JWT)。
   代价是重连要自己做,收益是能带令牌、能主动 abort。 */
export function orchestratorBase() {
  if (typeof window !== 'undefined' && window.__AICAP_ORCH_BASE__) return window.__AICAP_ORCH_BASE__
  if (import.meta.env.VITE_ORCH_BASE) return import.meta.env.VITE_ORCH_BASE
  return import.meta.env.PROD ? '' : 'http://127.0.0.1:8100'
}

/* 生产由 nginx 反代到 /orchestrator/(必须 proxy_buffering off,否则 SSE 退化成
   一次性吐出);开发直连 8100 根路径。 */
function orchestratorPath(path) {
  const base = orchestratorBase()
  if (!base) return '/orchestrator' + path
  return base.replace(/\/+$/, '') + path
}

/** 取后端 `{"detail": "..."}` 里的原因;解析不出 phrase 退到兜底文案。 */
async function readDetail(res, fallback) {
  try {
    const payload = await res.json()
    if (typeof payload.detail === 'string' && payload.detail.trim()) return payload.detail
  } catch (e) { /* 非 JSON 响应:保持兜底文案 */ }
  return fallback
}

/** 连不上编排层 / 编排层报平台不可达时抛这个 —— 供 UI 切「主管未连接」灰态。 */
export class SupervisorOfflineError extends Error {
  constructor(message) {
    super(message)
    this.name = 'SupervisorOfflineError'
    this.offline = true
  }
}

/** `GET /health` —— 自身存活 + Java 可达性 + 密钥是否配置。
    Java 不在时本端点**仍 200**,要看 `java` 字段(见 app/main.py#health)。 */
export async function chatHealth() {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 3000)
  try {
    const res = await fetch(orchestratorPath('/health'), { signal: ctrl.signal })
    if (!res.ok) return { reachable: false, java: false, llmConfigured: false }
    const payload = await res.json()
    return {
      reachable: true,
      java: Boolean(payload.java),
      llmConfigured: Boolean(payload.llm_configured)
    }
  } catch (e) {
    return { reachable: false, java: false, llmConfigured: false }
  } finally {
    clearTimeout(timer)
  }
}

/** `POST /chat` —— 起一轮。省 session_id 则后端新建会话。409 表示已有 run 在跑。
 *
 *  `projectId` 是**当前项目上下文**:多项目之后工具调用普遍需要 project_id,
 *  不带的话模型只能猜(读查询猜中过,触发型工具漏传会被平台以 invalid_arguments 拒)。
 *  省略仍然可用,只是回到"靠模型猜"的老行为。 */
export async function startChat(utterance, sessionId, projectId) {
  const token = getAuthToken()
  if (!token) throw new SupervisorOfflineError('未登录,无法与主管对话')
  const payload = { utterance }
  if (sessionId) payload.session_id = sessionId
  if (projectId) payload.project_id = projectId
  let res
  try {
    res = await fetch(orchestratorPath('/chat'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + token
      },
      body: JSON.stringify(payload)
    })
  } catch (e) {
    throw new SupervisorOfflineError('连接不上主管服务(编排层未启动?)')
  }
  if (!res.ok) {
    const detail = await readDetail(res, `请求失败 (${res.status})`)
    if (res.status === 503 || res.status === 401 || res.status === 403) throw new SupervisorOfflineError(detail)
    const error = new Error(detail)
    error.status = res.status
    throw error
  }
  return res.json()
}

/* ---------------------------------------------------------------
   SSE 帧解析。按规范切 `\n\n`;一帧里可能有 `id:` / `event:` / 多行 `data:`,
   以及以 `:` 开头的注释行(心跳,每 15s 一条,丢弃即可)。
   --------------------------------------------------------------- */
export function parseSseFrame(raw) {
  let id = null
  let event = null
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const idx = line.indexOf(':')
    const field = idx === -1 ? line : line.slice(0, idx)
    let value = idx === -1 ? '' : line.slice(idx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }
  if (!dataLines.length) return null
  try {
    return { id, event, payload: JSON.parse(dataLines.join('\n')) }
  } catch (e) {
    return null
  }
}

/**
 * 打开事件流,返回一个句柄。
 *
 * 契约要点(`app/main.py#_stream`):
 * - 终局(run.end / run.error)后**不关流**,`agent.report` 允许之后到达,所以这里
 *   拿到终局也不自动 close,由调用方决定。
 * - `seq` 来自 Java `agent_events.id` 自增主键,是全局唯一锚点 —— 重连续传用它。
 *
 * @param {object} o
 * @param {string} o.sessionId
 * @param {number} [o.afterSeq]
 * @param {(envelope:object)=>void} o.onEvent
 * @param {(err:Error)=>void} [o.onError]
 * @param {()=>void} [o.onClose]
 * @returns {{abort:Function, getCursor:Function}}
 */
export function openChatStream({ sessionId, afterSeq = 0, onEvent, onError, onClose }) {
  const token = getAuthToken()
  const ctrl = new AbortController()
  let closed = false
  let cursor = afterSeq

  function abort() {
    if (closed) return
    closed = true
    ctrl.abort()
  }

  async function pump() {
    if (!token) {
      onError?.(new SupervisorOfflineError('未登录,无法订阅主管事件流'))
      onClose?.()
      return
    }
    let res
    try {
      res = await fetch(
        orchestratorPath(`/chat/stream?session_id=${encodeURIComponent(sessionId)}&after_seq=${cursor}`),
        {
          headers: {
            Authorization: 'Bearer ' + token,
            // 显式带上,让编排层的惰性恢复判定与本地 cursor 一致(_start_seq 取 max)
            'Last-Event-ID': String(cursor),
            Accept: 'text/event-stream'
          },
          signal: ctrl.signal
        }
      )
    } catch (e) {
      if (!closed) { onError?.(new SupervisorOfflineError('事件流连接中断')); onClose?.() }
      return
    }
    if (!res.ok) {
      const detail = await readDetail(res, `事件流拒绝 (${res.status})`)
      if (!closed) {
        const err = new SupervisorOfflineError(detail)
        // 带上状态码:调用方要区分「会话在服务端已不存在(404)」与「服务临时不可用」。
        // 前者该丢句柄自愈,后者只能等(见 stores/supervisor.js#ensureStream)。
        err.status = res.status
        onError?.(err)
      }
      onClose?.()
      return
    }
    if (!res.body) {
      if (!closed) onError?.(new SupervisorOfflineError('浏览器不支持流式读取'))
      onClose?.()
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // SSE 帧分隔符是空行;\r\n\r\n 也接受(某些代理会改写换行)
        let sep
        while ((sep = buffer.search(/\r?\n\r?\n/)) !== -1) {
          const nl = buffer[sep] === '\r' ? 4 : 2
          const raw = buffer.slice(0, sep)
          buffer = buffer.slice(sep + nl)
          const frame = parseSseFrame(raw)
          if (!frame || !frame.payload) continue
          const env = frame.payload
          if (typeof env.seq === 'number') {
            // 重连续传时边界帧可能重复:严格大于才收(对齐 Java `id > after_seq`)
            if (env.seq <= cursor) continue
            cursor = env.seq
          }
          onEvent?.(env)
        }
      }
    } catch (e) {
      if (!closed) onError?.(new SupervisorOfflineError('事件流读取失败'))
    } finally {
      onClose?.()
    }
  }

  pump()
  return { abort, getCursor: () => cursor }
}
