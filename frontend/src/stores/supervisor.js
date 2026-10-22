/* 主管智能体(「本」)对话状态。
   ---------------------------------------------------------------
   三条值得留意的契约,它们决定了这个 store 的形状:

   1. **循环活在请求之外**。`POST /chat` 只负责「起一轮 + 拿回 handle」,真正的循环是
      编排层里的后台任务。所以 `/chat` 一返回就不能再等它 —— 要转去 `/chat/stream`
      看事件。这就是为什么 `running` 是靠事件推进的,不是靠 HTTP 响应。

   2. **终局后不关流**。契约 §3.4#4 允许 `agent.report` 在 `run.end` 之后到达,
      所以拿到 `done` 也不断开连接。流由 store 常驻持有:收起对话框、切页面、
      卸载组件都**不** abort —— 对话在 AI 办公室里是常驻的,浮窗只是它的另一个
      观察窗;只有 `resetSession`(换个话题)或 `send()` 换了会话时才断流重连。

   3. **seq 是唯一权威顺序**。事件可能重放+增量混着来;`timeline` 按 seq 排序而不是
      按到达顺序,这样断线重连后补来的历史事件会插到正确位置。 */
import { defineStore } from 'pinia'
import { SUPERVISOR_SESSION_KEY } from '@/constants'
import { chatHealth, openChatStream, startChat, SupervisorOfflineError } from '@/api/supervisor'
import { describeEvent } from '@/utils/supervisorTimeline'
import { useProjectsStore } from '@/stores/projects'

/** 上次的会话 handle —— 事件真相全在服务端,前端只需记住这个 id 就能续传 */
function loadSessionId() {
  try { return localStorage.getItem(SUPERVISOR_SESSION_KEY) || null } catch { return null }
}
function saveSessionId(id) {
  try {
    if (id) localStorage.setItem(SUPERVISOR_SESSION_KEY, id)
    else localStorage.removeItem(SUPERVISOR_SESSION_KEY)
  } catch { /* 浏览器不允许保存:本次会话内仍可用,仅仅不能跨刷新 */ }
}

export const useSupervisorStore = defineStore('supervisor', {
  state: () => ({
    open: false,
    /* 连接四态。与 /health 的三个字段对应 —— 三处的处置动作完全不同,
       不能合并成「在线/离线」两种:起编排层 / 起 Java 后端 / 配模型密钥。 */
    health: { reachable: false, java: false, llmConfigured: false },
    probing: false,

    sessionId: loadSessionId(),
    running: false,
    cursor: 0,

    /* 对话气泡:[{id, role:'user'|'agent', text, options?, runId?}] */
    messages: [],
    /* 时间轴:[{seq, runId, tone, label, text, detail, at}] —— 按 run_id 分组挂在
       对应的答复上,所以**不在新一轮开始时清空**:上一轮"本找了谁"要能翻回去看。
       这才是"过程可观测",清空的话就只剩最后一次的过程。 */
    timeline: [],

    notice: '',
    stream: null,
    _seq: 0
  }),

  getters: {
    /** 头像灯色:离线 / 平台不可达 / 未配密钥 / 在线 —— 四种一眼分得开 */
    status() {
      if (this.probing) return 'probing'
      if (!this.health.reachable) return 'offline'
      if (!this.health.java) return 'no-platform'
      if (!this.health.llmConfigured) return 'no-key'
      return 'online'
    },
    statusText() {
      return {
        probing: '探活中…',
        offline: '主管未连接(编排层未启动)',
        'no-platform': '平台后端不可达',
        'no-key': '未配置模型密钥',
        online: '在线'
      }[this.status]
    },
    /** 能不能发 —— 未连通时仍允许点开发历史的消息,但不能发新的 */
    canSend: s => s.health.reachable && s.health.java && !s.running
  },

  actions: {
    toggle() { this.open = !this.open; if (this.open) this.probe() },

    /** 探活。开窗时与发送前各调一次;不抛异常,失败只体现在 health 里 */
    async probe() {
      if (this.probing) return
      this.probing = true
      try {
        this.health = await chatHealth()
      } finally {
        this.probing = false
      }
    },

    /** 保证有一条事件流在跑。冪等:已在跑就复用。 */
    ensureStream() {
      if (this.stream || !this.sessionId) return
      const handle = openChatStream({
        sessionId: this.sessionId,
        afterSeq: this.cursor,
        onEvent: env => this.ingest(env),
        onError: err => {
          this.stream = null
          // 404 = 本地记着的会话在服务端已经不存在(开发库重建/数据被清)。
          // 这不是「离线」,不能把 health 打灰、也不该弹红字 —— 丢掉句柄就好,
          // 下次发送会新建会话。不自愈的话每次续流都撞同一个 404(刷新页面也不恢复,
          // 因为 id 持久化在 localStorage 里)。
          if (err.status === 404) {
            this.running = false
            this.dropSession()
            return
          }
          if (err instanceof SupervisorOfflineError) {
            this.health = { reachable: false, java: false, llmConfigured: false }
            this.running = false
            this.notice = err.message
          } else {
            this.notice = '事件流异常:' + err.message
          }
        },
        onClose: () => { this.stream = null }
      })
      this.stream = handle
    },

    /** 收一条事件:交给 describeEvent 翻译,再决定是进时间轴、还是成为对话气泡 */
    ingest(env) {
      const item = describeEvent(env)
      if (!item) return
      if (typeof env.seq === 'number') this.cursor = Math.max(this.cursor, env.seq)
      const seq = typeof env.seq === 'number' ? env.seq : ++this._seq

      this.timeline.push({ seq, runId: env.run_id, ...item })
      // 重连续传时历史事件可能补进来,按 seq 排以保证时间轴单调
      this.timeline.sort((a, b) => a.seq - b.seq)
      // 上限保护:一个长期不换话题的会话不至于把内存撑满
      if (this.timeline.length > 300) this.timeline.splice(0, this.timeline.length - 300)

      if (item.answer) {
        this.messages.push({
          id: 'a' + seq,
          role: 'agent',
          text: item.answer,
          options: item.options || null,
          runId: env.run_id
        })
      }
      if (item.done) this.running = false
    },

    async send(text) {
      const utterance = (text || '').trim()
      if (!utterance || this.running) return
      this.notice = ''
      await this.probe()
      if (!this.health.reachable || !this.health.java) return

      // 停在项目列表页(currentProjectId=null)时不发 /chat:那一轮注定只得到
      // 「缺 project_id」的反问,而反问给不出缺的是什么 —— 不如本地明说。
      // 不静默挑项目:替用户选一个项目等于让主管在错的上下文里干活。
      const projectId = useProjectsStore().currentProjectId
      if (!projectId) {
        this.messages.push({
          id: 'e' + Date.now(),
          role: 'agent',
          text: '请先进入一个具体项目,再和主管对话 —— 我需要知道你在说哪个项目。'
        })
        return
      }

      this.messages.push({ id: 'u' + Date.now(), role: 'user', text: utterance })
      this.running = true
      try {
        // 当前项目随每一轮带上:多项目之后工具调用普遍需要 project_id,
        // 不带就只能靠模型猜(见 api/supervisor.js 的 startChat 注释)
        // 记下**本次实际发出去**的那个 id:事件流那边的 404 也可能在同一时刻把
        // this.sessionId 清成 null,这时候若用 this.sessionId 判断"还有没有会话可丢",
        // 会把本该重试的 404 当成"没会话"直接抛给用户。
        const usedSessionId = this.sessionId
        let started
        try {
          started = await startChat(utterance, usedSessionId, projectId)
        } catch (err) {
          // 404 = 本地记着的会话在服务端已经不存在(开发库重建/数据被清)。
          // 不在这里自愈的话,之后每次发送都撞同一个 404,界面只显示「没能发出去:
          // 会话不存在」,刷新页面也不会恢复(localStorage 持久化)。
          // 丢掉句柄后把**这一次**用新会话重发;只重试一次,免得服务端持续 404 时打转。
          if (err.status !== 404 || !usedSessionId) throw err
          this.dropSession()
          started = await startChat(utterance, null, projectId)
        }
        const { session_id: sid } = started
        if (sid && sid !== this.sessionId) {
          this.sessionId = sid
          saveSessionId(sid)
          this.cursor = 0
          // 换了会话就丢了旧连接
          if (this.stream) { this.stream.abort(); this.stream = null }
        }
        this.ensureStream()
      } catch (err) {
        this.running = false
        this.messages.push({
          id: 'e' + Date.now(),
          role: 'agent',
          text: err instanceof SupervisorOfflineError ? err.message : ('没能发出去:' + err.message)
        })
      }
    },

    /** 丢掉本地会话句柄(会话在服务端已不存在时用):对话气泡保留,下次发送会新建会话。
     *  时间轴整段清掉 —— 它按 seq 排序,新旧两个会话的 seq 混在一起就没有顺序可言了。 */
    dropSession() {
      if (this.stream) this.stream.abort()
      this.stream = null
      this.sessionId = null
      saveSessionId(null)
      this.cursor = 0
      this.timeline = []
    },

    /** 「换个话题」:丢弃 session handle 与界面内容,下次发送会新建会话 */
    resetSession() {
      this.dropSession()
      this.messages = []
      this.running = false
      this.notice = ''
    }
  }
})
