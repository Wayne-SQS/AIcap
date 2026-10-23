import { api } from './client'

export async function getEfficiencyInsights(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/efficiency-insights`)
  if (result?.project_id !== projectId || result.basis !== 'task_blocked_and_status_history'
      || !Number.isInteger(result.open_tasks) || !Number.isInteger(result.missing_or_mismatched_history)
      || !Array.isArray(result.issues) || typeof result.limitations !== 'string') {
    throw new Error('效率分析结果格式无效')
  }
  return result
}
