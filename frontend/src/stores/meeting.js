import { defineStore } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useProjectsStore } from '@/stores/projects'
import { meetingsApi } from '@/api/meetings'
import { suggestionsApi } from '@/api/suggestions'

/* 会议域 store:对齐 meeting-review.js meetingReviewState + meeting-agent.js agentUI
   在线:会议/建议/Agent 配置与运行;离线由 AiView 走演示分支,不进本 store */
export const useMeetingStore = defineStore('meeting', {
  state: () => ({
    meetings: [],
    loadedProjectId: null,
    suggestions: [],
    selected: '',
    busy: [],           // 审核中的建议 id(旧版 Set,数组便于响应式)
    submission: null   // { fingerprint, key } 提交幂等键(网络失败后复用)
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
    selectedMeeting: s => s.meetings.find(m => m.id === s.selected) || null
  },
  actions: {
    async loadAll() {
      if (!this.online) return
      const projectId = useProjectsStore().currentProjectId
      if (!projectId) { this.meetings = []; this.suggestions = []; this.selected = ''; this.loadedProjectId = null; return }
      if (this.loadedProjectId !== projectId) {
        this.meetings = []; this.suggestions = []; this.selected = ''; this.loadedProjectId = projectId
      }
      const [meetings, suggestions] = await Promise.all([
        meetingsApi.list(projectId), suggestionsApi.list(projectId)
      ])
      if (!this.online || projectId !== useProjectsStore().currentProjectId) return
      this.meetings = meetings
      this.suggestions = suggestions
      if (!meetings.some(m => m.id === this.selected)) {
        this.selected = meetings[0]?.id || ''
      }
    },
    select(id) {
      this.selected = id
    },
    async saveMeeting(values) {
      const meeting = await meetingsApi.create(values, useProjectsStore().currentProjectId)
      this.selected = meeting.id
      this.meetings.unshift(meeting)
      return meeting
    },
    /* 删除会议(仅 admin/owner 由页面按钮把关,后端仍会 403 兜底):
       成功后再改本地态,失败(403/404/网络)保持列表与选中不变;
       删除后重新拉取列表并把选中复位为空,转写区与分析面板回到「未选择会议」 */
    async removeMeeting(id) {
      const result = await meetingsApi.remove(id, useProjectsStore().currentProjectId)
      this.selected = ''
      await this.loadAll()
      this.select('')   // loadAll 会自动补选第一条,这里显式复位保持空态
      return result
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
      const suggestion = await suggestionsApi.create(body, useProjectsStore().currentProjectId)
      this.suggestions = [suggestion, ...this.suggestions.filter(s => s.id !== suggestion.id)]
      return suggestion
    },
    async review(id, decision, reason, changes = null) {
      const payload = changes
        ? { decision: 'modify_and_approve', reason, changes }
        : { decision, reason }
      const result = await suggestionsApi.review(id, payload, useProjectsStore().currentProjectId)
      this.suggestions = this.suggestions.map(s => s.id === id ? result : s)
      return result
    }
  }
})
