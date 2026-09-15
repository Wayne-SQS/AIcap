/* 故事地图「马甲」(着色图层)与「排序」的唯一定义处
 *
 * 规则来自敏捷工具惯例(Jira 的卡片着色、Azure DevOps 的卡片规则/泳道):
 *  1. 单图层 —— 同一时刻只允许一个着色维度生效。多维度叠加会变成「彩虹图」,
 *     颜色互相干扰后反而没有区分度,这是故事地图最典型的信息设计反模式。
 *  2. 图例常驻且写明规则,不能只写颜色名;灰色表示「未知/无排期」,必须单列,
 *     不能并进「正常」——「不知道」和「没问题」是两件事。
 *  3. 颜色之外必须叠加字符标记(glyph)。红绿是最差的色盲组合,只靠色相传达语义
 *     对色觉障碍用户等于没有信息。
 *
 * 四个图层各自回答一个管理问题:
 *   紧急程度 → 哪里会延期;负责人 → 负载是否均衡;Sprint → 这个版本装了多少;
 *   无着色   → 保持基线原貌(评审/截图/对照时用)。
 *
 * 本模块只做「故事 → 呈现」的纯计算,不依赖 Pinia store:成员色与姓名由调用方注入,
 * 这样它可以脱离组件被直接推演与测试。
 */
import { URGENCY, URGENCY_ORDER, urgencyOf, urgencyRank, deadlineWeekOf, sprintEndWeek } from './planCalendar'
import { MAP_SPRINTS } from './seed'

/** 可选马甲;默认「紧急程度」(地图上最需要先看到的就是风险分布) */
export const SKINS = [
  { key: 'urgency', label: '紧急程度' },
  { key: 'owner', label: '负责人' },
  { key: 'sprint', label: 'Sprint' },
  { key: 'none', label: '无着色' }
]

/** 格内排序;「原次序」= 数据自身顺序(基线即 US01→US37),不重新发明顺序 */
export const SORTS = [
  { key: 'origin', label: '原次序' },
  { key: 'urgency', label: '紧急程度' },
  { key: 'owner', label: '负责人' }
]

/**
 * 单张卡片的马甲呈现。
 * @param {object} story 故事
 * @param {object} ctx { skin, tasks, nowWeek, memberColor(id), memberName(id), memberCount }
 * @returns {{tone:string, glyph:string, label:string, detail:string}|null} skin='none' 时返回 null
 */
export function skinOf(story, ctx) {
  const { skin, tasks = [], nowWeek = 1 } = ctx
  if (skin === 'none') return null

  if (skin === 'urgency') {
    const u = urgencyOf(story, tasks, nowWeek)
    const deadline = deadlineWeekOf(story, tasks)
    const detail = deadline == null ? '未排期' : `截止 W${deadline}`
    return { tone: u.tone, glyph: u.glyph, label: u.label, detail }
  }

  if (skin === 'owner') {
    const idx = story.owner == null ? null : story.owner
    return {
      tone: 'tone-' + (ctx.memberColor ? ctx.memberColor(idx) : 'gray'),
      glyph: idx == null ? '—' : 'P' + (idx + 1),
      label: ctx.memberName ? ctx.memberName(idx) : '未分配',
      detail: idx == null ? '未分配负责人' : ''
    }
  }

  if (skin === 'sprint') {
    const sp = MAP_SPRINTS.find(x => x.matches(story)) || MAP_SPRINTS[0]
    const end = sprintEndWeek(sp.number)
    return {
      tone: 'tone-' + sp.cls,
      glyph: 'S' + sp.number,
      label: `${sp.name} · ${sp.tag}`,
      detail: end == null ? '六周计划之外' : `截止 W${end}`
    }
  }

  return null
}

/**
 * 常驻图例:每个色块都带字符标记与实时计数。
 * 计数口径 = 当前筛选范围(与地图上可见卡片一一对应,可相加校验)。
 */
export function legendOf(skin, data, ctx) {
  if (skin === 'none') return []

  if (skin === 'urgency') {
    /* 图例必须写明**规则**(阈值)而不只是颜色名:看到"红色 10 条"没用,
       看到「截止周 < W3」才知道这个红是按什么算出来的,也才可能在周推进后自己复算。 */
    const w = ctx.nowWeek
    const rule = {
      overdue: `截止周 < W${w}`,
      due: `截止周 = W${w}`,
      soon: `截止周 W${w + 1}–W${w + 2}`,
      later: `截止周 ≥ W${w + 3}`,
      roadmap: '未排期,不编造日期',
      done: '不参与风险统计'
    }
    return URGENCY_ORDER.map(key => {
      const def = URGENCY[key]
      return { ...def, detail: rule[key], count: data.filter(s => urgencyOf(s, ctx.tasks, ctx.nowWeek).key === key).length }
    })
  }

  if (skin === 'owner') {
    const rows = (ctx.members || []).map(m => ({
      tone: 'tone-' + (ctx.memberColor ? ctx.memberColor(m.id) : 'gray'),
      glyph: 'P' + (m.id + 1),
      label: m.name,
      detail: '',
      count: data.filter(s => s.owner === m.id).length
    }))
    const unassigned = data.filter(s => s.owner == null).length
    if (unassigned) rows.push({ tone: 'tone-gray', glyph: '—', label: '未分配', detail: '', count: unassigned })
    /* 兜底:负责人下标不在成员表内时(理论上被 checkConsistency 拦下),图例计数必须仍然守恒,
       否则「合计 = 当前筛选」这条可相加校验会莫名其妙对不上。 */
    const covered = rows.reduce((a, r) => a + r.count, 0)
    if (covered < data.length) {
      rows.push({ tone: 'tone-muted', glyph: '?', label: '未知成员', detail: '不在成员表内', count: data.length - covered })
    }
    return rows
  }

  if (skin === 'sprint') {
    return MAP_SPRINTS.map(sp => {
      const end = sprintEndWeek(sp.number)
      return {
        tone: 'tone-' + sp.cls,
        glyph: 'S' + sp.number,
        label: `${sp.name} · ${sp.tag}`,
        detail: end == null ? '六周计划之外' : `截止 W${end}`,
        count: data.filter(sp.matches).length
      }
    })
  }

  return []
}

/**
 * 格内排序(不改动原数组)。并列时保持原次序,避免同一格每次渲染都换位置。
 * @param {Array} list 同一「活动 × 切片」格内的故事
 * @param {string} sortBy SORTS 的 key
 */
export function sortStories(list, sortBy, ctx) {
  if (sortBy === 'origin' || !sortBy) return list
  const copy = [...list]
  if (sortBy === 'urgency') {
    return copy.sort((a, b) => urgencyRank(a, ctx.tasks, ctx.nowWeek) - urgencyRank(b, ctx.tasks, ctx.nowWeek))
  }
  if (sortBy === 'owner') {
    /* 未分配(null)排在最后,不与末尾成员混在一起 */
    const key = s => (s.owner == null ? 999 : s.owner)
    return copy.sort((a, b) => key(a) - key(b))
  }
  return copy
}
