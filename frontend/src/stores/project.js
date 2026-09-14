import { defineStore } from 'pinia'
import { storiesApi } from '@/api/stories'
import { tasksApi } from '@/api/tasks'
import { poolApi } from '@/api/pool'
import { authApi } from '@/api/auth'
import { STORIES_KEY, LOG_KEY, POOL_KEY } from '@/constants'
import { SEED, TASKS, POOL_SEED, MEMBERS, MEMBER_COLORS, ROLE_TXT, derivedTaskSprints } from '@/data/seed'
import { memberIndex, memberUserId, memberLabel, isContiguousUserIds } from '@/data/memberIdentity'

/* 项目数据基座:stories/tasks/pool/log 四份数据 + members(真实成员)

   - 在线:loadAll() 走 API(mapStory 契约映射 owner_id-1 → 下标),并合并 /api/auth/users 的
     真实姓名/角色/容量(后端 users.capacity_hours)。
   - 离线:落回 seed.js 的 US01–US37 基线(与后端 DataSeeder 同源)。
   - 血缘口径保持:v2 的「看板↔甘特强制血缘」不变(subTasksOf/cardPct/groupPct),
     此处新增 mcc 新版引入的共享只读选择器(任务状态/负载/依赖/一致性自检),四视图共用一套算法。
*/

function validStory(x) {
  return typeof x.id === 'string' && typeof x.title === 'string' && typeof x.description === 'string' &&
    typeof x.acceptance === 'string' && ['Must', 'Should', 'Could'].includes(x.priority) &&
    [0, 1, 2].includes(x.status) && Number.isInteger(x.sprint) && x.sprint >= 1 &&
    [null, 0, 1, 2, 3, 4].includes(x.owner) && [1, 2, 3, 4, 5].includes(x.activity)
}

function loadStories() {
  let ok = true
  let stories = structuredClone(SEED)
  try {
    const saved = JSON.parse(localStorage.getItem(STORIES_KEY))
    if (Array.isArray(saved) && saved.length && saved.every(validStory)) {
      // 旧基线(M01–M23)存量缓存:与 US01–US37 新基线不同源,直接落回种子而不是混着用
      const legacy = saved.every(x => /^M\d+$/.test(x.id))
      if (legacy) console.info('[AICAP] 检测到旧基线缓存(M01–M23),已改用 US01–US37 种子数据')
      else stories = saved
    }
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

/** 每周计划工时:向下取整 + 余数前倾(与旧版 loadByOwner 口径一致) */
function weeklyHoursOf(task, out) {
  const start = Math.max(1, task.w[0]), end = Math.min(6, task.w[1]), count = Math.max(1, end - start + 1)
  const base = Math.floor(task.h / count)
  let remainder = task.h - base * count
  for (let w = start; w <= end; w++) out[w - 1] = base + (remainder-- > 0 ? 1 : 0)
  return out
}

/** 任务引用字段(T 编号 / US 编号)统一解析:逗号分隔 + 去空白 */
export function taskRefs(value) {
  return String(value || '').split(',').map(x => x.trim()).filter(Boolean)
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
      members: structuredClone(MEMBERS),
      serverSync: false,  // 在线且已完成 loadAll(控制 #savehint 文案)
      savehint: '拖动卡片更新状态 · 点击编辑 · 自动保存本地'
    }
  },
  getters: {
    /* ==================== 血缘(看板 ↔ 甘特) ==================== */
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
    storyProgress: s => (list = null) => {
      const arr = list || s.stories
      const total = arr.length, done = arr.filter(x => x.status === 2).length, doing = arr.filter(x => x.status === 1).length
      return { total, done, doing, todo: total - done - doing, percent: total ? Math.round(done / total * 100) : 0 }
    },

    /* ==================== 成员与容量 ==================== */
    memberById: s => id => s.members.find(m => m.id === id) || null,
    memberName: s => id => (s.members.find(m => m.id === id) || {}).name || '未分配',
    memberColor: s => id => MEMBER_COLORS[id] || 'gray',
    /** 展示用角色:后端角色名 + 职责尾段(如「管理员 · 总协调」);只读查看者按职责显示 */
    memberRoleText: s => id => {
      const m = s.members.find(x => x.id === id)
      if (!m) return '未分配'
      if (m.roleKey === 'viewer') return m.role
      const tail = String(m.role || '').split(' · ').pop()
      return tail && tail !== m.role ? `${ROLE_TXT[m.roleKey] || m.roleKey} · ${tail}` : m.role
    },
    memberTasks: s => id => s.tasks.filter(t => t.owner === id),
    capacityHours: s => id => Math.max(0, Number((s.members.find(x => x.id === id) || {}).capacity) || 0),
    allocatedHours: s => id => s.tasks.filter(t => t.owner === id).reduce((sum, t) => sum + Math.max(0, Number(t.h) || 0), 0),
    utilization: s => id => {
      const cap = Number((s.members.find(x => x.id === id) || {}).capacity) || 0
      return cap ? s.tasks.filter(t => t.owner === id).reduce((sum, t) => sum + Math.max(0, Number(t.h) || 0), 0) / cap : 0
    },
    /** 利用率 → 负载档位(超载 / 偏忙 / 正常 / 充足),成员页与热力图例共用 */
    capacityStatus: () => utilization => {
      if (utilization > 1) return { key: 'over', label: '超载' }
      if (utilization >= .8) return { key: 'busy', label: '偏忙' }
      if (utilization >= .6) return { key: 'normal', label: '正常' }
      return { key: 'low', label: '充足' }
    },
    memberWeeklyLoad: s => id => {
      const out = Array(6).fill(0)
      s.tasks.filter(t => t.owner === id).forEach(task => weeklyHoursOf(task, Array(6).fill(0)).forEach((h, i) => { out[i] += h }))
      return out
    },
    loadByOwner: s => s.members.map(m => {
      const out = Array(6).fill(0)
      s.tasks.filter(t => t.owner === m.id).forEach(task => weeklyHoursOf(task, out))
      return out
    }),

    /* ==================== 任务状态与依赖 ==================== */
    taskStatusKey: () => task => {
      if (task.status === 3) return 'cancelled'
      if (task.blocked) return 'blocked'
      if (task.status === 2 || task.progress >= 100) return 'done'
      if (task.status === 1 || task.progress > 0) return 'doing'
      return 'todo'
    },
    taskStatusText: () => task => {
      if (task.status === 3) return '已取消'
      if (task.blocked) return '阻塞'
      if (task.status === 2 || task.progress >= 100) return '已完成'
      if (task.status === 1 || task.progress > 0) return '进行中'
      return '待办'
    },
    taskById: s => id => s.tasks.find(t => t.id === id) || null,
    taskName: s => id => {
      const t = s.tasks.find(x => x.id === id)
      return t ? `${id} ${t.name}` : id
    },
    taskSprintList: () => task => (Array.isArray(task.sprints) && task.sprints.length)
      ? task.sprints : derivedTaskSprints(task.w[0], task.w[1]),
    taskSprintText: () => task => ((Array.isArray(task.sprints) && task.sprints.length)
      ? task.sprints : derivedTaskSprints(task.w[0], task.w[1])).map(s => 'S' + s).join(' / '),
    predecessorsOf: s => id => taskRefs((s.tasks.find(t => t.id === id) || {}).dependsOn),
    dependentsOf: s => id => s.tasks.filter(t => taskRefs(t.dependsOn).includes(id)).map(t => t.id),
    storiesForTask: s => task => taskRefs(task.story)
      .map(id => s.stories.find(x => x.id === id.toUpperCase())).filter(Boolean)
  },
  actions: {
    /** 新建故事的编号分配:US 命名空间顺延,与后端 StoryController.nextStoryId 的 US%02d 口径一致
     *  (契约:`qa/vue-baseline-e2e.spec.js` FE-POOL-03 断言新故事必须落在 US38+)。离线新建与
     *  「需求池 → 移入看板」共用此入口,避免两处各写一遍编号规则而漂移。 */
    nextStoryId() {
      const max = this.stories.reduce((acc, x) => {
        const m = /^[A-Za-z]+(\d+)$/.exec(String(x.id))
        return m ? Math.max(acc, Number(m[1])) : acc
      }, 37)  // 基线 US01–US37 之后从 US38 起
      return 'US' + String(max + 1).padStart(2, '0')
    },
    /* 契约映射层:后端字段 → 前端模型。owner_id → 成员下标的换算统一走 memberIdentity
       (偏移= id-1 是位置对齐,全部集中在那一处,并带连续性告警) */
    mapStory(s) {
      return {
        id: s.id, title: s.title, description: s.description, acceptance: s.acceptance,
        priority: s.priority, sprint: s.sprint, activity: s.activity, status: s.status,
        owner: memberIndex(s.owner_id)
      }
    },
    mapTask(t) {
      return {
        id: t.id, name: t.name, owner: memberIndex(t.owner_id), h: t.hours,
        eh: t.estimated_hours != null ? t.estimated_hours : t.hours,
        w: [t.week_start, t.week_end], story: t.story_ref || '',
        card: t.kanban_card_id || null, type: t.task_type || 'feature',
        dependsOn: t.depends_on || '',
        status: t.status != null ? t.status : 0,
        progress: t.progress != null ? t.progress : 0,
        blocked: Boolean(t.blocked),
        sprints: (Array.isArray(t.sprints) && t.sprints.length) ? t.sprints : derivedTaskSprints(t.week_start, t.week_end)
      }
    },
    /** 用后端真实用户覆盖成员姓名/角色/容量(容量口径统一到 users.capacity_hours) */
    mergeMembers(users) {
      if (!Array.isArray(users) || !users.length) return
      const byId = new Map(users.map(u => [u.id, u]))
      /* 位置对齐的前提是 users.id 恰为 1..N 连续;不连续时 `id-1` 会出现空洞,
         空洞处的成员会保留种子姓名(表现为「负责人姓名对不上」),这里明确告警而不是静默错配。 */
      if (!isContiguousUserIds(users.map(u => u.id))) {
        console.warn('[AICAP member-identity] users.id 非 1..N 连续,前端成员下标(= id-1)存在空洞,可能出现负责人姓名错配:',
          users.map(u => u.id).sort((a, b) => a - b))
      }
      this.members = this.members.map(m => {
        const u = byId.get(memberUserId(m.id))
        if (!u) return m
        return {
          ...m,
          name: u.display_name || u.username,
          roleKey: u.role || m.roleKey,
          capacity: u.capacity_hours || m.capacity
        }
      })
      // 后端用户多于本地种子时补齐,避免真实成员在视图里缺失
      users.forEach(u => {
        const idx = memberIndex(u.id)
        if (!this.members.some(m => m.id === idx)) {
          this.members.push({
            id: idx, name: u.display_name || u.username, role: ROLE_TXT[u.role] || u.role,
            roleKey: u.role, tag: '', capacity: u.capacity_hours || 60,
            accent: MEMBER_COLORS[idx] || 'gray'
          })
        }
      })
      this.members.sort((a, b) => a.id - b.id)
    },
    async loadAll() {
      const [st, tk, pl, lg, users] = await Promise.all([
        storiesApi.list(), tasksApi.list(), poolApi.list(), storiesApi.logs(), authApi.users()
      ])
      this.stories = st.map(this.mapStory)
      this.tasks = tk.map(this.mapTask)
      this.mergeMembers(users)
      this.pool = pl.map(p => ({ id: p.id, title: p.title, desc: p.description, source: p.source, created: '—', priority: p.priority }))
      /* 后端 /api/stories/logs 按 id 倒序(最新在前):取最新 50 条后翻成「旧 → 新」,
         与本机 addlog 的追加口径统一;展示层(ChangeLogPanel/最近更新)再按最新在前处理 */
      this.log = lg.map(l => ({ t: (l.created_at || '').slice(11, 19), type: l.log_type, id: l.story_id, detail: l.detail })).slice(0, 50).reverse()
      this.serverSync = true
      this.savehint = '已连接后端 · 数据实时同步到服务器'
      this.checkConsistency()
    },
    restoreLocal() {
      const init = loadStories()
      this.stories = init.stories
      this.pool = loadPool()
      this.tasks = structuredClone(TASKS)
      this.members = structuredClone(MEMBERS)
      this.log = loadLog()
      this.serverSync = false
      this.savehint = '拖动卡片更新状态 · 点击编辑 · 自动保存本地'
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
    },
    /** 一致性自检(只告警不改数):编号重复、负责人/工时/周次非法、关联 Story 缺失、挂载看板卡不存在、
     *  Sprint 口径不一致、子任务全完成但故事未标记完成、成员下标不连续、旧版 M 编号残留 */
    checkConsistency() {
      const warnings = [], errors = []
      const storyIds = new Set(), taskIds = new Set()
      this.stories.forEach(s => {
        if (storyIds.has(s.id)) errors.push(`Story ID 重复：${s.id}`)
        storyIds.add(s.id)
      })
      this.tasks.forEach(t => {
        if (taskIds.has(t.id)) errors.push(`Task ID 重复：${t.id}`)
        taskIds.add(t.id)
        if (!this.members.some(m => m.id === t.owner)) errors.push(`${t.id} 负责人不存在：${memberLabel(t.owner)}`)
        if (!Number.isFinite(Number(t.h)) || Number(t.h) < 0) errors.push(`${t.id} 工时无效：${t.h}`)
        if (t.w[0] < 1 || t.w[1] > 6 || t.w[0] > t.w[1]) errors.push(`${t.id} 周次超出 W1-W6：W${t.w[0]}-W${t.w[1]}`)
        const missing = taskRefs(t.story).map(id => id.toUpperCase()).filter(id => !storyIds.has(id))
        if (missing.length) errors.push(`${t.id} 关联 Story 不存在：${missing.join('、')}`)
        /* 孤儿挂卡:kanban_card_id 指向不存在的看板卡(删除故事的级联失效时会留下)。
           与上面的 story_ref 检查是两回事:story_ref 是文本引用,kanban_card_id 是甘特父行的分组键,
           悬挂时甘特会渲染出没有标题的父条。 */
        if (t.card && !storyIds.has(t.card)) errors.push(`${t.id} 挂载的看板卡不存在：${t.card}`)
        const sprints = new Set(Array.isArray(t.sprints) && t.sprints.length ? t.sprints : derivedTaskSprints(t.w[0], t.w[1]))
        const storySprints = [...new Set(this.storiesForTask(t).map(s => s.sprint))]
        if (storySprints.some(sp => !sprints.has(sp))) {
          warnings.push(`${t.id} 的执行 Sprint 与关联 Story Sprint 不完全一致`)
        }
      })
      /* 故事 ↔ 子任务状态一致性(仅告警,不改数):子任务已全部完成或取消,但故事自身仍未标记完成。
         注意反向那条(故事已完成而子任务未完成)不在此处告警——基线种子 US01 本就如此,
         加了会在每次数据变化时刷常驻噪音;而这一条在 US01–US37 + T01–T16 基线上命中数为 0。 */
      this.stories.forEach(s => {
        const subs = this.subTasksOf(s.id)
        if (!subs.length || s.status === 2) return
        if (subs.every(t => t.status === 2 || t.status === 3)) {
          warnings.push(`${s.id} 的子任务已全部完成或取消,故事状态仍为「${['待办', '进行中', '已完成'][s.status] || s.status}」`)
        }
      })
      /* 成员下标必须连续 0..N-1:后端 users.id 一旦断开(= id-1 出现空洞),
         位置对齐就会把负责人与姓名错配,而视图上不会报错、只会显示错的人名 */
      const memberIds = this.members.map(m => m.id).sort((a, b) => a - b)
      if (memberIds.some((id, i) => id !== i)) {
        errors.push(`成员下标不连续：${memberIds.join('、')}（后端 users.id 必须为 1..N 连续）`)
      }
      this.members.forEach(m => { if ((Number(m.capacity) || 0) <= 0) errors.push(`成员 ${m.name} 容量无效`) })
      const legacy = [...storyIds].filter(id => /^M\d+$/.test(id))
      if (legacy.length) warnings.push(`发现旧版 Story 编号：${legacy.join('、')}`)
      const result = { warnings, errors }
      if (warnings.length || errors.length) console.warn('[AICAP consistency]', result)
      return result
    }
  }
})
