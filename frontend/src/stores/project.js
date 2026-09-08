import { defineStore } from 'pinia'
import { storiesApi } from '@/api/stories'
import { tasksApi } from '@/api/tasks'
import { poolApi } from '@/api/pool'
import { STORIES_KEY, LOG_KEY, POOL_KEY } from '@/constants'
import { SEED, TASKS, POOL_SEED } from '@/data/seed'

/* 项目数据域:stories/tasks/pool/log 四份数据 + 在线拉取(mapStory 契约映射) + 离线 restore/persist
   血缘派生计算(subTasksOf/cardPct/groupPct/loadByOwner)对齐旧版 L875-892 / L1037-1041 */

function validStory(x) {
  return typeof x.id === 'string' && typeof x.title === 'string' && typeof x.description === 'string' &&
    typeof x.acceptance === 'string' && ['Must', 'Should', 'Could'].includes(x.priority) &&
    [0, 1, 2].includes(x.status) && [1, 2, 3].includes(x.sprint) &&
    [null, 0, 1, 2, 3].includes(x.owner) && [1, 2, 3, 4, 5].includes(x.activity)
}

function loadStories() {
  let ok = true
  let stories = structuredClone(SEED)
  try {
    const saved = JSON.parse(localStorage.getItem(STORIES_KEY))
    if (Array.isArray(saved) && saved.length && saved.every(validStory)) stories = saved
  } catch { ok = false }
  return { stories, storageOK: ok }
}

function loadPool() {
  try {
    const sl = JSON.parse(localStorage.getItem(POOL_KEY))
    if (Array.isArray(sl) && sl.length) return sl
  } catch { /* ignore */ }
  return structuredClone(POOL_SEED)
}

function loadLog() {
  try {
    const sl = JSON.parse(localStorage.getItem(LOG_KEY))
    if (Array.isArray(sl)) return sl.slice(-50)
  } catch { /* ignore */ }
  return []
}

export const useProjectStore = defineStore('project', {
  state: () => {
    const init = loadStories()
    return {
      stories: init.stories,
      storageOK: init.storageOK,
      tasks: structuredClone(TASKS),
      pool: loadPool(),
      log: loadLog(),
      serverSync: false  // 在线且已完成 loadAll(控制 #savehint 文案)
    }
  },
  getters: {
    subTasksOf: s => cardId => s.tasks.filter(t => t.card === cardId && t.type === 'feature'),
    cardPct() {
      return s => {
        const subs = this.subTasksOf(s.id)
        if (!subs.length) return s.status === 2 ? 1 : (s.status === 1 ? 0.5 : 0)
        const total = subs.reduce((a, t) => a + (t.eh || t.h), 0)
        const done = subs.filter(t => t.status === 2).reduce((a, t) => a + (t.eh || t.h), 0)
        return total ? done / total : 0
      }
    },
    groupPct() {
      return list => {
        if (!list.length) return 0
        let doneW = 0, totalW = 0
        list.forEach(s => {
          const subs = this.subTasksOf(s.id)
          if (subs.length) {
            totalW += subs.reduce((a, t) => a + (t.eh || t.h), 0)
            doneW += subs.filter(t => t.status === 2).reduce((a, t) => a + (t.eh || t.h), 0)
          } else {
            totalW += 1
            doneW += s.status === 2 ? 1 : (s.status === 1 ? 0.5 : 0)
          }
        })
        return totalW ? Math.round(doneW / totalW * 100) : 0
      }
    },
    loadByOwner: s => {
      const map = [0, 1, 2, 3].map(() => [0, 0, 0, 0, 0, 0])
      s.tasks.forEach(t => { for (let w = t.w[0]; w <= t.w[1]; w++) { map[t.owner][w - 1] += Math.round(t.h / (t.w[1] - t.w[0] + 1)) } })
      return map
    }
  },
  actions: {
    /* 契约映射层:后端字段 → 前端模型(owner_id-1 偏移是 Spring Boot 重写时唯一需复核点) */
    mapStory(s) {
      return {
        id: s.id, title: s.title, description: s.description, acceptance: s.acceptance,
        priority: s.priority, sprint: s.sprint, activity: s.activity, status: s.status,
        owner: (s.owner_id == null ? null : s.owner_id - 1)
      }
    },
    async loadAll() {
      const [st, tk, pl, lg] = await Promise.all([
        storiesApi.list(), tasksApi.list(), poolApi.list(), storiesApi.logs()
      ])
      this.stories = st.map(this.mapStory)
      this.tasks = tk.map(t => ({
        id: t.id, name: t.name, owner: t.owner_id - 1, h: t.hours,
        eh: t.estimated_hours != null ? t.estimated_hours : t.hours,
        w: [t.week_start, t.week_end], story: t.story_ref,
        card: t.kanban_card_id || null, type: t.task_type || 'feature',
        status: t.status != null ? t.status : 0
      }))
      this.pool = pl.map(p => ({ id: p.id, title: p.title, desc: p.description, source: p.source, created: '—', priority: p.priority }))
      this.log = lg.map(l => ({ t: (l.created_at || '').slice(11, 19), type: l.log_type, id: l.story_id, detail: l.detail })).slice(-50)
      this.serverSync = true
    },
    restoreLocal() {
      this.stories = loadStories().stories
      this.pool = loadPool()
      this.tasks = structuredClone(TASKS)
      this.log = loadLog()
      this.serverSync = false
    },
    persist() {
      try {
        localStorage.setItem(STORIES_KEY, JSON.stringify(this.stories))
        this.storageOK = true
      } catch { this.storageOK = false }
    },
    persistPool() {
      try { localStorage.setItem(POOL_KEY, JSON.stringify(this.pool)) } catch { /* ignore */ }
    },
    addlog(type, id, detail) {
      this.log.push({ t: new Date().toLocaleTimeString('zh-CN', { hour12: false }), type, id, detail: detail || '' })
      if (this.log.length > 50) this.log = this.log.slice(-50)
      try { localStorage.setItem(LOG_KEY, JSON.stringify(this.log)) } catch { /* ignore */ }
    }
  }
})
