/* 计划日历与紧急度:故事地图「紧急程度」马甲的唯一定义处
 *
 * 为什么需要这个文件
 * ------------------
 * 「紧急程度」图层要回答的管理问题是「哪里会延期」,而延期 = 截止周 − 当前周。
 * 但项目里此前没有任何「现在」的锚点:Story 输出没有日期字段(见 StoryDtos.StoryOut)、
 * Task 只有 week_start/week_end 计划周次(W1–W6),全仓唯一叫 deadline 的
 * `deadline_text` 是会议智能体的自由文本(未解析,不属于项目侧数据模型)。
 * 与其在各个组件里各自猜时间,不如把「现在」显式定义成一个可解释、可测试的量。
 *
 * 口径(与 MAP_SPRINTS / derivedTaskSprints 对齐)
 * --------------------------------------------
 *  - 项目起点 PROJECT_START = 2026-08-31(周一)。依据:会议记录落在 2026-09-06(W1 末),
 *    六周计划 W1–W6 与故事地图的 4 个发布切片一一对应。
 *  - 每 2 周一个 Sprint:W1–W2 = S1、W3–W4 = S2、W5–W6 = S3(与 derivedTaskSprints 同式)。
 *  - 故事的截止周 = 所挂子任务的最晚计划周 week_end(12 条故事有真实排期,口径更精确);
 *    没有子任务的故事退回所属切片末周(其余 25 条)。
 *    Sprint 4+ 的故事落在六周计划之外 → 无排期(灰),不编造日期(反模式「假精确」)。
 *  - 档位:gap = 截止周 − 当前周;<0 逾期、=0 本周到期、1–2 临近、≥3 宽松。
 *
 * 可测试性
 * --------
 * `window.__AICAP_TODAY__` 存在时优先作为「现在」(与 __AICAP_API_BASE__ 同一注入约定),
 * 否则用本机日期。这样 E2E 断言不随真实日期漂移(否则过了 9 月这套用例集体变红),
 * 也方便人工演示任意时间点的风险分布。
 */

/** 项目起点(周一) */
export const PROJECT_START = '2026-08-31'

/** 计划周数:W1–W6(六周学期计划) */
export const PLAN_WEEKS = 6

/** 每 2 周一个 Sprint(与 seed.js derivedTaskSprints 同式) */
export function sprintOfWeek(week) {
  return Math.floor((week - 1) / 2) + 1
}

/** 切片末周:S1→W2 / S2→W4 / S3→W6;Sprint 4+ 在六周计划之外 → null(后续路线) */
export const SPRINT_END_WEEK = { 1: 2, 2: 4, 3: 6 }

export function sprintEndWeek(sprint) {
  return SPRINT_END_WEEK[sprint] ?? null
}

/** 「现在」:注入优先,其次本机日期 */
function nowDate(now) {
  if (now) return now
  if (typeof window !== 'undefined' && window.__AICAP_TODAY__) return new Date(window.__AICAP_TODAY__ + 'T00:00:00')
  return new Date()
}

/** 当前周:起点所在周为 W1;早于起点按 W1(不出现 0/负周)。上限不夹紧——
 *  真实日期一旦超出六周,「逾期」应当如实反映,而不是被夹到 W6 假装还在计划内。 */
export function currentWeek(now) {
  const start = new Date(PROJECT_START + 'T00:00:00')
  const days = Math.floor((nowDate(now) - start) / 86400000)
  if (!Number.isFinite(days)) return 1
  return Math.max(1, Math.floor(days / 7) + 1)
}

/** 第 N 周对应的日期区间文案(用于今日锚点与图例说明) */
export function weekRangeText(week) {
  const start = new Date(PROJECT_START + 'T00:00:00')
  const fmt = d => `${d.getMonth() + 1}/${d.getDate()}`
  const from = new Date(start.getTime() + (week - 1) * 7 * 86400000)
  const to = new Date(from.getTime() + 6 * 86400000)
  return `${fmt(from)}–${fmt(to)}`
}

/** 「今天」锚点的展示文案:超出六周计划时如实说明,不假装还在计划内 */
export function todayText(week) {
  if (week > PLAN_WEEKS) return `今天 · 第 ${week} 周（已超出六周计划）`
  return `今天 · 第 ${week} 周 · Sprint ${sprintOfWeek(week)}`
}

/**
 * 故事的截止周:优先取所挂子任务的最晚计划周,无子任务则退回切片末周。
 * @returns {number|null} null 表示无排期(不编造日期)
 */
export function deadlineWeekOf(story, tasks = []) {
  const subs = tasks.filter(t => t.card === story.id && t.type === 'feature')
  if (subs.length) {
    const ends = subs.map(t => Number((t.w || [])[1])).filter(n => Number.isFinite(n) && n > 0)
    if (ends.length) return Math.max(...ends)
  }
  return sprintEndWeek(story.sprint)
}

/** 紧急度档位定义。`glyph` 是颜色之外的第二通道(色盲友好):不得只靠色相区分。 */
export const URGENCY = {
  done: { key: 'done', label: '已完成', tone: 'tone-muted', glyph: '✓' },
  overdue: { key: 'overdue', label: '逾期', tone: 'tone-red', glyph: '!' },
  due: { key: 'due', label: '本周到期', tone: 'tone-orange', glyph: '▲' },
  soon: { key: 'soon', label: '临近（1–2 周）', tone: 'tone-yellow', glyph: '·' },
  later: { key: 'later', label: '宽松（≥3 周）', tone: 'tone-green', glyph: '○' },
  roadmap: { key: 'roadmap', label: '后续路线（无排期）', tone: 'tone-gray', glyph: '?' }
}

/** 图例固定顺序:先风险后中性,灰色(未知)单列不并入「正常」 */
export const URGENCY_ORDER = ['overdue', 'due', 'soon', 'later', 'roadmap', 'done']

/**
 * 单条故事的紧急度档位。
 * @param {object} story 故事(需 status / sprint)
 * @param {Array} tasks 全部任务(取挂在故事上的子任务算真实截止周)
 * @param {number} nowWeek 当前周(currentWeek())
 */
export function urgencyOf(story, tasks, nowWeek) {
  if (story.status === 2) return URGENCY.done
  const deadline = deadlineWeekOf(story, tasks)
  if (deadline == null) return URGENCY.roadmap
  const gap = deadline - nowWeek
  if (gap < 0) return URGENCY.overdue
  if (gap === 0) return URGENCY.due
  if (gap <= 2) return URGENCY.soon
  return URGENCY.later
}

/** 紧急度排序键:逾期的排最前,越紧急越小;无排期与已完成压到最后(并列时按原次序稳定) */
export function urgencyRank(story, tasks, nowWeek) {
  if (story.status === 2) return 999
  const deadline = deadlineWeekOf(story, tasks)
  if (deadline == null) return 998
  return deadline - nowWeek
}
