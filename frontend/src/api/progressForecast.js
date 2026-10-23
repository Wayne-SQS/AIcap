import { api } from './client'

const RECORD_STATUSES = ['proposed', 'accepted', 'discarded']
const VERDICTS = ['not_baseline', 'reached', 'overdue', 'within_plan', 'no_date']

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

function assertRecord(projectId, record) {
  if (!record || record.project_id !== projectId || !Number.isInteger(record.id)
      || !RECORD_STATUSES.includes(record.record_status) || typeof record.assumptions !== 'string'
      || !Number.isInteger(record.tasks_open) || !Number.isInteger(record.tasks_done)
      || !record.reconciliation || !VERDICTS.includes(record.reconciliation.verdict)) {
    throw new Error('预测基线结果格式无效')
  }
  return record
}

/** Freeze the current forecast as a proposal; accepting it is a separate, explicit step. */
export async function recordProgressForecast(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/progress-forecast/records`, { method: 'POST' })
  return assertRecord(projectId, result)
}

export async function getProgressForecastRecords(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/progress-forecast/records`)
  if (result?.project_id !== projectId || !Array.isArray(result.records) || typeof result.limitations !== 'string') {
    throw new Error('预测基线列表格式无效')
  }
  return { ...result, records: result.records.map(record => assertRecord(projectId, record)) }
}

export async function decideProgressForecast(projectId, recordId, decision, note) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/progress-forecast/records/${encodeURIComponent(recordId)}/decision`, {
    method: 'POST', body: JSON.stringify(note ? { decision, note } : { decision })
  })
  return assertRecord(projectId, result)
}
