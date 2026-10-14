// Find the longest dependency chain by adding each task's planned duration.
// The sum is a chain weight, not the elapsed calendar time on the Gantt chart.
export function criticalPath(tasks) {
  const active = tasks.filter(task => task.status !== 3)
  const byId = new Map(active.map(task => [task.id, task]))
  const children = new Map(active.map(task => [task.id, []]))
  const indegree = new Map(active.map(task => [task.id, 0]))
  const duration = task => Math.max(1, Number(task.w?.[1]) - Number(task.w?.[0]) + 1 || 1)

  for (const task of active) {
    const dependencies = new Set(String(task.dependsOn || '').split(',').map(id => id.trim()).filter(Boolean))
    for (const id of dependencies) {
      if (!byId.has(id)) continue
      children.get(id).push(task.id)
      indegree.set(task.id, indegree.get(task.id) + 1)
    }
  }

  const queue = active.filter(task => indegree.get(task.id) === 0).map(task => task.id)
  const score = new Map(active.map(task => [task.id, duration(task)]))
  const parent = new Map()
  let visited = 0
  for (let index = 0; index < queue.length; index++) {
    const id = queue[index]
    visited++
    for (const child of children.get(id)) {
      const candidate = score.get(id) + duration(byId.get(child))
      if (candidate > score.get(child)) {
        score.set(child, candidate)
        parent.set(child, id)
      }
      indegree.set(child, indegree.get(child) - 1)
      if (indegree.get(child) === 0) queue.push(child)
    }
  }

  if (visited !== active.length) return { ids: [], weeks: 0, cycle: true }
  if (!active.length) return { ids: [], weeks: 0, cycle: false }
  let end = active[0].id
  for (const task of active) if (score.get(task.id) > score.get(end)) end = task.id
  const ids = []
  for (let id = end; id; id = parent.get(id)) ids.unshift(id)
  return { ids, weeks: score.get(end), cycle: false }
}
