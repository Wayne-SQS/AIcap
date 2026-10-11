import { reactive } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { useToast } from '@/composables/useToast'
import { storiesApi } from '@/api/stories'
import { tasksApi } from '@/api/tasks'
import { agentApi } from '@/api/agent'
import { derivedTaskSprints } from '@/data/seed'

const saving = reactive(new Set())

function weeklyHours(task) {
  const out = Array(6).fill(0)
  const start = Math.max(1, Number(task.w?.[0]) || 1)
  const end = Math.min(6, Number(task.w?.[1]) || start)
  const span = Math.max(1, end - start + 1)
  const hours = Math.max(0, Number(task.h) || 0)
  const base = Math.floor(hours / span)
  let remainder = hours - base * span
  for (let week = start; week <= end; week++) out[week - 1] = base + (remainder-- > 0 ? 1 : 0)
  return out
}

function groupWarnings(warnings) {
  const groups = {
    dependency: { type: 'dependency', title: '任务依赖冲突', items: [] },
    load: { type: 'load', title: '成员负载超限', items: [] },
    sprint: { type: 'sprint', title: 'Sprint 边界冲突', items: [] },
    other: { type: 'other', title: '其他影响', items: [] }
  }
  warnings.forEach(text => {
    if (/^W\d+ /.test(text) || text.includes('负载')) groups.load.items.push(text)
    else if (text.includes('Sprint')) groups.sprint.items.push(text)
    else if (/^T\d+ /.test(text) || text.includes('依赖')) groups.dependency.items.push(text)
    else groups.other.items.push(text)
  })
  return Object.values(groups).filter(group => group.items.length)
}

const IMPACT_GROUPS = [
  ['loadRisk', '成员负载分析'],
  ['dependencyRisk', '任务依赖分析'],
  ['sprintRisk', 'Sprint 冲突分析'],
  ['delayRisk', '延期风险分析'],
  ['futureTaskImpact', '后续任务影响']
]

function emptyImpactReport() {
  return Object.fromEntries(IMPACT_GROUPS.map(([key]) => [key, []]))
}

function addImpact(report, key, message, entity = '') {
  report[key].push({ message, entity })
}

function finalizeAnalysis(report, impacts) {
  const warnings = [...new Set(IMPACT_GROUPS.flatMap(([key]) => report[key].map(item => item.message)))]
  const groups = IMPACT_GROUPS
    .map(([type, title]) => ({ type, title, items: report[type].map(item => item.message) }))
    .filter(group => group.items.length)
  return { warnings, groups, impacts, impactReport: report }
}

function analysisFromReport(report, impacts = []) {
  const normalized = { ...emptyImpactReport(), ...(report || {}) }
  return finalizeAnalysis(normalized, impacts)
}

export function useProjectActions() {
  const project = useProjectStore()
  const session = useSessionStore()
  const { guard } = usePermissionGuard()
  const { notify } = useToast()
  const isSaving = key => saving.has(key)

  async function analyzeTaskSchedule(taskId, weekStart, weekEnd) {
    const task = project.taskById(taskId)
    const start = Number(weekStart), end = Number(weekEnd)
    if (!task || !Number.isInteger(start) || !Number.isInteger(end)) return { warnings: [], groups: [], impacts: [] }
    const report = emptyImpactReport()
    project.predecessorsOf(task.id).forEach(id => {
      const predecessor = project.taskById(id)
      if (predecessor && predecessor.w[1] > start) addImpact(report, 'dependencyRisk', `${id} 在 W${predecessor.w[1]} 结束，晚于新开始周 W${start}`, id)
    })
    project.dependentsOf(task.id).forEach(id => {
      const dependent = project.taskById(id)
      if (dependent && dependent.w[0] < end) {
        addImpact(report, 'dependencyRisk', `${id} 在 W${dependent.w[0]} 开始，早于新结束周 W${end}`, id)
        addImpact(report, 'futureTaskImpact', `${id} 依赖 ${task.id}，调整后可能延期`, id)
      }
    })
    const proposedSprints = new Set(derivedTaskSprints(start, end))
    if (project.storiesForTask(task).some(story => !proposedSprints.has(story.sprint))) addImpact(report, 'sprintRisk', '新排期与关联 Story Sprint 不完全一致', task.id)
    const other = project.tasks.filter(item => item.owner === task.owner && item.id !== task.id)
    const proposed = weeklyHours({ ...task, w: [start, end] })
    const beforeLoad = project.memberWeeklyLoad(task.owner)
    const capacity = project.capacityHours(task.owner) / 6
    for (let week = 1; week <= 6; week++) {
      const load = other.reduce((sum, item) => sum + weeklyHours(item)[week - 1], 0) + proposed[week - 1]
      if (capacity && load > capacity) addImpact(report, 'loadRisk', `W${week}：修改前 ${beforeLoad[week - 1] || 0}h，修改后 ${load}h，超过周容量 ${capacity.toFixed(1)}h`, task.id)
    }
    const before = weeklyHours(task)
    const changedWeeks = before.map((hours, index) => hours !== proposed[index] ? `W${index + 1}` : null).filter(Boolean)
    const impacts = [{ entity: task.id, kind: 'task_schedule', before: `W${task.w[0]}-W${task.w[1]}`, after: `W${start}-W${end}`, detail: changedWeeks.length ? `影响 ${changedWeeks.join('、')} 负载` : '排期已计算' }]
    if (end > task.w[1]) addImpact(report, 'delayRisk', `${task.id} 预计延期 ${end - task.w[1]} 周：W${task.w[0]}-W${task.w[1]} → W${start}-W${end}`, task.id)
    const fallback = finalizeAnalysis(report, impacts)
    if (!session.apiMode) return fallback
    try { return analysisFromReport(await agentApi.planning.impact([{ type: 'UPDATE_TASK_SCHEDULE', task_id: taskId, start_week: start, end_week: end }]), impacts) } catch { return fallback }
  }

  async function analyzeTaskOwner(taskId, ownerId) {
    const task = project.taskById(taskId)
    const target = project.memberById(Number(ownerId))
    if (!task || !target) return { warnings: [], groups: [], impacts: [] }
    const beforeOwner = project.memberById(task.owner)
    const before = project.memberWeeklyLoad(target.id)
    const after = before.slice()
    weeklyHours(task).forEach((hours, index) => { after[index] += hours })
    const capacity = project.capacityHours(target.id) / 6
    const report = emptyImpactReport()
    after.forEach((hours, index) => {
      if (hours > capacity) addImpact(report, 'loadRisk', `成员 ${target.name} W${index + 1}：修改前 ${before[index] || 0}h，修改后 ${hours}h，周容量 ${capacity.toFixed(1)}h`, task.id)
    })
    const impacts = [{ entity: task.id, kind: 'task_owner', before: beforeOwner?.name || '未分配', after: target.name, detail: `目标成员 ${target.name} 的六周负载将重新计算` }]
    const fallback = finalizeAnalysis(report, impacts)
    if (!session.apiMode) return fallback
    try { return analysisFromReport(await agentApi.planning.impact([{ type: 'UPDATE_TASK_OWNER', task_id: taskId, to_owner_id: target.id + 1 }]), impacts) } catch { return fallback }
  }

  async function analyzeStorySprint(storyId, sprint) {
    const story = project.stories.find(item => item.id === storyId)
    if (!story) return { warnings: [], groups: [], impacts: [] }
    const report = emptyImpactReport()
    project.subTasksOf(story.id)
      .filter(task => !project.taskSprintList(task).includes(Number(sprint)))
      .forEach(task => addImpact(report, 'sprintRisk', `Sprint 冲突：${task.id} 的执行周与 ${story.id} 的 Sprint ${sprint} 不一致`, task.id))
    const impacts = [{ entity: story.id, kind: 'story_sprint', before: story.sprint, after: Number(sprint), detail: '关联任务排期保持不变' }]
    const fallback = finalizeAnalysis(report, impacts)
    if (!session.apiMode) return fallback
    try { return analysisFromReport(await agentApi.planning.impact([{ type: 'UPDATE_STORY_SPRINT', story_id: storyId, sprint: Number(sprint) }]), impacts) } catch { return fallback }
  }

  async function run(key, action, optimistic, rollback, save) {
    if (guard(action)) return { ok: false, reason: 'readonly' }
    if (saving.has(key)) return { ok: false, reason: 'saving' }
    saving.add(key)
    optimistic()
    try {
      await save(session.apiMode)
      if (session.apiMode) await project.loadAll()
      else project.checkConsistency()
      notify('已更新')
      return { ok: true }
    } catch (error) {
      rollback()
      notify(`更新失败：${error.message}`)
      return { ok: false, reason: error.message }
    } finally {
      saving.delete(key)
    }
  }

  async function updateStoryPlacement(storyId, { sprint, activity }) {
    const story = project.stories.find(item => item.id === storyId)
    if (!story) return { ok: false, reason: '故事不存在' }
    const next = { sprint: Number(sprint), activity: Number(activity) }
    if (story.sprint === next.sprint && story.activity === next.activity) return { ok: true, unchanged: true }
    const before = { sprint: story.sprint, activity: story.activity }
    return run(`story:${storyId}`, '调整故事位置', () => Object.assign(story, next), () => Object.assign(story, before), async online => {
      if (online) await storiesApi.patch(storyId, next)
      else {
        project.addlog('move', storyId, `移动到 Sprint ${next.sprint} / A${next.activity}`)
        project.persist()
      }
    })
  }

  async function updateTaskSchedule(taskId, { weekStart, weekEnd }) {
    const task = project.tasks.find(item => item.id === taskId)
    if (!task) return { ok: false, reason: '任务不存在' }
    const start = Number(weekStart), end = Number(weekEnd)
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end > 6 || start > end) {
      notify('更新失败：排期必须位于 W1-W6，且结束周不能早于开始周')
      return { ok: false, reason: 'invalid-range' }
    }
    if (task.w[0] === start && task.w[1] === end) return { ok: true, unchanged: true }
    const before = { w: [...task.w], sprints: [...task.sprints] }
    const nextSprints = derivedTaskSprints(start, end)
    return run(`task:${taskId}`, '调整任务排期', () => { task.w = [start, end]; task.sprints = nextSprints }, () => { task.w = before.w; task.sprints = before.sprints }, async online => {
      if (online) await tasksApi.patch(taskId, { week_start: start, week_end: end })
      else {
        project.addlog('edit', taskId, `排期 W${start}-W${end}`)
        project.persistTasks()
      }
    })
  }

  async function updateTaskOwner(taskId, ownerId) {
    const task = project.tasks.find(item => item.id === taskId)
    const owner = project.members.find(item => item.id === Number(ownerId))
    if (!task || !owner) return { ok: false, reason: '任务或负责人不存在' }
    if (task.owner === owner.id) return { ok: true, unchanged: true }
    const before = task.owner
    return run(`task:${taskId}`, '调整任务负责人', () => { task.owner = owner.id }, () => { task.owner = before }, async online => {
      if (online) await tasksApi.patch(taskId, { owner_id: owner.id + 1 })
      else {
        project.addlog('edit', taskId, `负责人改为 ${owner.name}`)
        project.persistTasks()
      }
    })
  }

  return { saving, isSaving, updateStoryPlacement, updateTaskSchedule, updateTaskOwner, analyzeTaskSchedule, analyzeTaskOwner, analyzeStorySprint }
}
