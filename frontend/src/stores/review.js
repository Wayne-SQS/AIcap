import { defineStore } from 'pinia'
import { SUG_KEY, DEC_KEY } from '@/constants'
import { SUGGESTION_SEED } from '@/data/seed'

/* AI 建议审核域:suggestions(待审清单) + decisions(决策留痕)
   对齐旧版 L1145-1179 的 localStorage 读写与 decideSug 语义(在线审核中心对接在 PR-5) */
function loadSug() {
  try {
    const sl = JSON.parse(localStorage.getItem(SUG_KEY))
    if (Array.isArray(sl) && sl.length) return sl
  } catch { /* ignore */ }
  return structuredClone(SUGGESTION_SEED)
}
function loadDec() {
  try {
    const sl = JSON.parse(localStorage.getItem(DEC_KEY))
    if (Array.isArray(sl)) return sl
  } catch { /* ignore */ }
  return []
}

export const useReviewStore = defineStore('review', {
  state: () => ({
    suggestions: loadSug(),
    decisions: loadDec()
  }),
  actions: {
    decide(id, act, reason) {
      const s = this.suggestions.find(x => x.id === id)
      if (!s) return
      s.status = act
      const actTxt = act === 'approved' ? '采纳' : (act === 'modified' ? '修改后采纳' : '拒绝')
      this.decisions.push({ t: new Date().toLocaleTimeString('zh-CN', { hour12: false }), id, act: actTxt, reason: reason || '' })
      if (this.decisions.length > 50) this.decisions = this.decisions.slice(-50)
      try {
        localStorage.setItem(SUG_KEY, JSON.stringify(this.suggestions))
        localStorage.setItem(DEC_KEY, JSON.stringify(this.decisions))
      } catch { /* ignore */ }
      return actTxt
    }
  }
})
