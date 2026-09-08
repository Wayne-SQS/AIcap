import { defineStore } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { meetingsApi } from '@/api/meetings'
import { suggestionsApi } from '@/api/suggestions'
import { agentApi } from '@/api/agent'

/* 会议域 store:对齐 meeting-review.js meetingReviewState + meeting-agent.js agentUI
   在线:会议/建议/Agent 配置与运行;离线由 AiView 走演示分支,不进本 store */
export const useMeetingStore = defineStore('meeting', {
  state: () => ({
    meetings: [],
    suggestions: [],
    selected: '',
    busy: [],           // 审核中的建议 id(旧版 Set,数组便于响应式)
    submission: null,   // { fingerprint, key } 提交幂等键(网络失败后复用)
    agentConfig: null,
    agentRun: null,     // 当前选中会议的最新运行
    agentError: '',
    agentLoading: false
  }),
  getters: {
    online: () => {
      const s = useSessionStore()
      return s.apiMode && !!s.currentUser
    },
    maySubmit: () => {
      const s = useSessionStore()
      return !!s.currentUser && ['admin', 'owner', 'member'].includes(s.currentUser.role)
    },
    mayReview: () => {
      const s = useSessionStore()
      return !!s.currentUser && ['admin', 'owner'].includes(s.currentUser.role)
    },
    selectedMeeting: s => s.meetings.find(m => m.id === s.selected) || null,
    runActive: s => !!s.agentRun && ['queued', 'running'].includes(s.agentRun.status)
  },
  actions: {
    async loadAll() {
      if (!this.online) return
      const [meetings, suggestions, config] = await Promise.all([
        meetingsApi.list(), suggestionsApi.list(), agentApi.config().catch(() => null)
      ])
      this.meetings = meetings
      this.suggestions = suggestions
      if (config) this.agentConfig = config
      if (!meetings.some(m => m.id === this.selected)) {
        this.selected = meetings[0]?.id || ''
      }
    },
    select(id) {
      this.selected = id
      this.agentRun = null
      this.agentError = ''
    },
    async saveMeeting(values) {
      const meeting = await meetingsApi.create(values)
      this.selected = meeting.id
      this.meetings.unshift(meeting)
      return meeting
    },
    /* 手动录入待审建议:同一指纹复用 client_request_id,防网络重试导致重复入库 */
    async submitProposal(form) {
      const body = {
        meeting_id: this.selected, action: 'pool.create', origin: 'manual',
        evidence: form.evidence, note: form.note,
        changes: { title: form.title, description: form.description, priority: form.priority }
      }
      const fingerprint = JSON.stringify(body)
      if (!this.submission || this.submission.fingerprint !== fingerprint) {
        this.submission = { fingerprint, key: crypto.randomUUID() }
      }
      body.client_request_id = this.submission.key
      const suggestion = await suggestionsApi.create(body)
      this.suggestions = [suggestion, ...this.suggestions.filter(s => s.id !== suggestion.id)]
      return suggestion
    },
    async review(id, decision, reason, changes = null) {
      const payload = changes
        ? { decision: 'modify_and_approve', reason, changes }
        : { decision, reason }
      const result = await suggestionsApi.review(id, payload)
      this.suggestions = this.suggestions.map(s => s.id === id ? result : s)
      return result
    },
    /* Agent 运行:refreshRun 由组件轮询驱动(generation 竞态防护在组件内) */
    async refreshRun() {
      const id = this.selected
      if (!this.online || !id) return
      const rows = await meetingsApi.runs(id)
      const run = rows[0] ? await meetingsApi.getRun(rows[0].id) : null
      if (this.selected !== id) return  // 已切换会议,丢弃过期结果
      this.agentRun = run
      if (run && ['awaiting_review', 'completed'].includes(run.status)) {
        this.suggestions = await suggestionsApi.list()
      }
      return run
    },
    async startRun(retryId = null) {
      const id = this.selected
      this.agentError = ''
      this.agentLoading = true
      try {
        const run = retryId
          ? await meetingsApi.retryRun(retryId)
          : await meetingsApi.startRun(id)
        if (this.selected === id) this.agentRun = run
        return run
      } finally {
        this.agentLoading = false
      }
    },
    /* 复制会议并重新分析 */
    async reanalyze() {
      const original = this.selectedMeeting
      const meeting = await meetingsApi.create({
        title: original.title.slice(0, 190) + ' · 重新分析',
        transcript: original.transcript
      })
      this.meetings.unshift(meeting)
      this.selected = meeting.id
      return meeting
    }
  }
})
