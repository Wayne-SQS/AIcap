const LEGACY_CAPACITY_WEEKS = 6
const DEFAULT_WEEKLY_CAPACITY = [15, 14, 16, 13, 12]

function positiveNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}

function plannedWeeks(tasks = [], stories = []) {
  const taskWeeks = tasks.reduce((max, task) => Math.max(max, Number(task?.w?.[1]) || 0), 0)
  const storyWeeks = stories.reduce((max, story) => Math.max(max, (Number(story?.sprint) || 0) * 2), 0)
  return Math.max(taskWeeks, storyWeeks)
}

export function projectWeeks(project, tasks = [], stories = []) {
  const start = project?.start_date || project?.startDate
  const end = project?.end_date || project?.endDate
  if (start && end) {
    const startDate = new Date(`${start}T00:00:00`)
    const endDate = new Date(`${end}T00:00:00`)
    const days = Math.floor((endDate - startDate) / 86400000) + 1
    if (Number.isFinite(days) && days > 0) return Math.max(1, Math.ceil(days / 7))
  }
  const explicitWeeks = positiveNumber(project?.duration_weeks || project?.durationWeeks)
  if (explicitWeeks) return Math.round(explicitWeeks)
  const scheduledWeeks = plannedWeeks(tasks, stories)
  if (scheduledWeeks) return scheduledWeeks
  const sprints = positiveNumber(project?.total_sprints || project?.totalSprints)
  if (sprints) return Math.round(sprints * 2)
  return LEGACY_CAPACITY_WEEKS
}

export function defaultWeeklyCapacity(member) {
  const rawId = Number(member?.userId ?? member?.user_id ?? member?.id)
  if (!Number.isFinite(rawId) || rawId < 0) return 0
  const id = Math.floor(rawId)
  return DEFAULT_WEEKLY_CAPACITY[id % DEFAULT_WEEKLY_CAPACITY.length]
}

/** Explicit weekly capacity wins; legacy capacity_hours is only a no-identity compatibility fallback. */
export function weeklyCapacity(member) {
  const explicit = positiveNumber(member?.weeklyCapacity ?? member?.weekly_capacity_hours)
  if (explicit) return explicit
  const fallback = defaultWeeklyCapacity(member)
  if (fallback) return fallback
  const legacy = positiveNumber(member?.legacyCapacity ?? member?.capacity_hours ?? member?.capacity)
  if (legacy) return legacy / LEGACY_CAPACITY_WEEKS
  return 0
}

export function totalCapacity(member, project, tasks = [], stories = []) {
  return weeklyCapacity(member) * projectWeeks(project, tasks, stories)
}

export function capacityBreakdown(member, project, tasks = [], stories = []) {
  const weeks = projectWeeks(project, tasks, stories)
  const weekly = weeklyCapacity(member)
  return { weeks, weekly, total: weekly * weeks }
}
