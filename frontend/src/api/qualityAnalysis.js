import { api } from './client'

export async function getQualityAnalysis(projectId) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/quality-analysis`)
  if (result?.project_id !== projectId || result.basis !== 'story_task_specification_and_quality_evidence'
      || !Number.isInteger(result.stories_total) || !Number.isInteger(result.open_tasks)
      || !Array.isArray(result.findings) || !Array.isArray(result.improvements)
      || typeof result.limitations !== 'string') {
    throw new Error('质量分析结果格式无效')
  }
  for (const finding of result.findings) {
    if (!finding?.kind || !finding.source_code || !finding.source_name
        || typeof finding.category !== 'string' || typeof finding.location !== 'string'
        || typeof finding.detail !== 'string' || typeof finding.suggestion !== 'string') {
      throw new Error('质量分析结果格式无效')
    }
  }
  if (result.evidence !== undefined && (result.evidence === null || typeof result.evidence !== 'object')) {
    throw new Error('质量分析结果格式无效')
  }
  return result
}

export function createQualityImprovement(projectId, sourceCode, payload) {
  return api(`/api/projects/${encodeURIComponent(projectId)}/quality-analysis/${encodeURIComponent(sourceCode)}/improvements`, {
    method: 'POST', body: JSON.stringify(payload)
  })
}

export async function createQualityTestSet(projectId, payload) {
  const result = await api(`/api/projects/${encodeURIComponent(projectId)}/quality-analysis/test-sets`, {
    method: 'POST', body: JSON.stringify(payload)
  })
  if (!result?.code || typeof result.name !== 'string' || !Array.isArray(result.cases)
      || !Array.isArray(result.covered_boundaries) || !Array.isArray(result.missing_boundaries)) {
    throw new Error('测试集分析结果格式无效')
  }
  return result
}
