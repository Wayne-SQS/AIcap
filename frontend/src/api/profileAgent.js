import { api } from './client'

/* AI 任务提交与成员能力画像智能体:分析/活动/难度/快照/运行记录 */
export const profileAgentApi = {
  /** 整包分析(只读预览) */
  analysis: (start, end) => api(`/api/profile-agent/analysis?start=${start}&end=${end}`),
  /** 正式分析(留痕,可重试) */
  run: (start, end) => api(`/api/profile-agent/analysis/run?start=${start}&end=${end}`, { method: 'POST' }),
  /** 运行记录 */
  runs: () => api('/api/profile-agent/runs'),
  /** 活动列表 */
  activities: (params = {}) => {
    const qs = new URLSearchParams()
    if (params.userId != null) qs.set('userId', params.userId)
    if (params.start) qs.set('start', params.start)
    if (params.end) qs.set('end', params.end)
    const q = qs.toString()
    return api('/api/profile-agent/activities' + (q ? '?' + q : ''))
  },
  /** 录入活动事实 */
  addActivity: payload => api('/api/profile-agent/activities', { method: 'POST', body: JSON.stringify(payload) }),
  /** 任务难度(AI 评估+人工覆盖) */
  difficulty: () => api('/api/profile-agent/difficulty'),
  /** 人工修正难度 */
  overrideDifficulty: (taskId, payload) =>
    api(`/api/profile-agent/difficulty/${taskId}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  /** 团队风险 → 送审(统一 AI 建议审核中心) */
  submitRiskSuggestions: (start, end) =>
    api(`/api/profile-agent/risks/submit-suggestions?start=${start}&end=${end}`, { method: 'POST' }),
  /** 画像快照 */
  snapshots: (userId = null) => api('/api/profile-agent/snapshots' + (userId != null ? `?userId=${userId}` : '')),
  /** 画像快照趋势(变化原因) */
  snapshotTrend: (userId = null) => api('/api/profile-agent/snapshots/trend' + (userId != null ? `?userId=${userId}` : '')),
  /** 贡献绿格子热力图(按日聚合) */
  heatmap: (start, end, userId = null) => {
    let q = `start=${start}&end=${end}`
    if (userId != null) q += `&userId=${userId}`
    return api(`/api/profile-agent/heatmap?${q}`)
  },
  /** 时间范围对比(本期 vs 上期) */
  compare: (prevStart, prevEnd, start, end) =>
    api(`/api/profile-agent/analysis/compare?prevStart=${prevStart}&prevEnd=${prevEnd}&start=${start}&end=${end}`),
  /** 提交画像纠正(本人或 admin/owner) */
  addCorrection: (userId, field, correctedValue, reason = '') =>
    api(`/api/profile-agent/corrections?userId=${userId}&field=${encodeURIComponent(field)}&correctedValue=${encodeURIComponent(correctedValue)}&reason=${encodeURIComponent(reason)}`, { method: 'POST' }),
  /** 画像纠正历史 */
  corrections: (userId = null) => api('/api/profile-agent/corrections' + (userId != null ? `?userId=${userId}` : '')),
  /** GitHub 同步状态(US34) */
  githubStatus: () => api('/api/profile-agent/github/status'),
  /** 手动触发 GitHub 活动同步(admin/owner) */
  githubSync: (start, end) => api(`/api/profile-agent/github/sync?start=${start}&end=${end}`, { method: 'POST' })
}
