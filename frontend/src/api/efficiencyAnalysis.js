import { api } from './client'
const base = id => `/api/projects/${encodeURIComponent(id)}/efficiency-analysis`
export const efficiencyAnalysisApi = {
  latest: id => api(`${base(id)}/latest`),
  run: id => api(`${base(id)}/runs`, { method: 'POST' }),
  get: (id, runId) => api(`${base(id)}/runs/${encodeURIComponent(runId)}`),
  submit: (id, runId, recommendationId) => api(`${base(id)}/runs/${encodeURIComponent(runId)}/recommendations/${encodeURIComponent(recommendationId)}/submit`, { method: 'POST' })
  ,planApproved: (id, proposalId) => api(`${base(id)}/proposals/${encodeURIComponent(proposalId)}/planning-run`, { method: 'POST' })
  ,check: (id, proposalId) => api(`${base(id)}/proposals/${encodeURIComponent(proposalId)}/checks`, { method: 'POST' })
}
