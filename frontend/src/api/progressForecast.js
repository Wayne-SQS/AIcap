import { api } from './client'

export async function getProgressForecast(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/progress-forecast`)
  if (result?.project_id !== projectId || !['forecast', 'insufficient_data', 'complete', 'empty_scope'].includes(result.status)
      || result.basis !== 'task_status_history' || !Number.isInteger(result.tasks_open)
      || !Number.isInteger(result.tasks_done) || !Number.isInteger(result.recent_completions)
      || !Number.isInteger(result.observed_days) || typeof result.assumptions !== 'string') {
    throw new Error('进度预测结果格式无效')
  }
  if (result.status === 'forecast' && (!/^\d{4}-\d{2}-\d{2}$/.test(result.estimated_date)
      || !Number.isInteger(result.estimated_remaining_days) || result.estimated_remaining_days < 1
      || typeof result.completions_per_week !== 'number')) throw new Error('进度预测依据不完整')
  return result
}
