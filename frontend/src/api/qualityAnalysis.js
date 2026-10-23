import { api } from './client'

export async function getQualityAnalysis(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/quality-analysis`)
  if (result?.project_id !== projectId || result.basis !== 'story_and_task_specification_fields'
      || !Number.isInteger(result.stories_total) || !Number.isInteger(result.open_tasks)
      || !Array.isArray(result.findings) || !Array.isArray(result.improvements)
      || typeof result.limitations !== 'string') {
    throw new Error('质量分析结果格式无效')
  }
  for (const finding of result.findings) {
    if (!finding?.kind || !finding.source_code || !finding.source_name
        || typeof finding.detail !== 'string' || typeof finding.suggestion !== 'string') {
      throw new Error('质量分析结果格式无效')
    }
  }
  return result
}

export function createQualityImprovement(projectId, sourceCode, payload) {
  return api(`/api/projects/${encodeURIComponent(projectId)}/quality-analysis/${encodeURIComponent(sourceCode)}/improvements`, {
    method: 'POST', body: JSON.stringify(payload)
  })
}
