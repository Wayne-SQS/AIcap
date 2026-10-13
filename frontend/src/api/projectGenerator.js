import { api, LONG_TIMEOUT_MS } from './client'

export const projectGeneratorApi = {
  /* 采用 LHWAN 的多项目/新建项目接口面(projectId 作用域 + createNew/importNew/... ),
     同时保留本分支为长任务加的显式超时:项目生成与表格导入都是分钟级 LLM 作业,
     用 api() 默认超时会中途断掉。 */
  create: (mode, request, strategy, projectId) => api('/api/project-generator/runs', { method: 'POST', body: JSON.stringify({ mode, request, strategy, project_id: projectId }), timeoutMs: LONG_TIMEOUT_MS }),
  createNew: (mode, request, project) => api('/api/project-generator/runs', { method: 'POST', body: JSON.stringify({ mode, request, strategy: 'create', context: 'create', project_name: project?.name || '', project_description: project?.description || '', total_sprints: Number(project?.total_sprints) || 3 }), timeoutMs: LONG_TIMEOUT_MS }),
  importFile: (file, strategy, projectId) => { const body = new FormData(); body.append('file', file); return api('/api/project-generator/import?strategy=' + encodeURIComponent(strategy) + '&projectId=' + encodeURIComponent(projectId), { method: 'POST', body, timeoutMs: LONG_TIMEOUT_MS }) },
  importNew: (file, project) => { const body = new FormData(); body.append('file', file); const query = new URLSearchParams({ context: 'create', strategy: 'create', projectName: project.name || '', projectDescription: project.description || '', totalSprints: String(project.total_sprints || 0) }); return api('/api/project-generator/import?' + query, { method: 'POST', body, timeoutMs: LONG_TIMEOUT_MS }) },
  get: (id, projectId) => api(`/api/project-generator/runs/${id}?projectId=${encodeURIComponent(projectId)}`),
  getNew: id => api(`/api/project-generator/runs/${id}?context=create`),
  updateStory: (runId, storyId, changes, projectId) => api(`/api/project-generator/runs/${runId}/stories/${encodeURIComponent(storyId)}?projectId=${encodeURIComponent(projectId)}`, { method: 'PATCH', body: JSON.stringify(changes) }),
  updateStoryNew: (runId, storyId, changes) => api(`/api/project-generator/runs/${runId}/stories/${encodeURIComponent(storyId)}?context=create`, { method: 'PATCH', body: JSON.stringify(changes) }),
  confirm: (id, replaceConfirmed, projectId) => api(`/api/project-generator/runs/${id}/confirm?projectId=${encodeURIComponent(projectId)}`, { method: 'POST', body: JSON.stringify({ replace_confirmed: replaceConfirmed }) }),
  confirmNew: id => api(`/api/project-generator/runs/${id}/confirm?context=create`, { method: 'POST' }),
  cancel: (id, projectId) => api(`/api/project-generator/runs/${id}/cancel?projectId=${encodeURIComponent(projectId)}`, { method: 'POST' }),
  cancelNew: id => api(`/api/project-generator/runs/${id}/cancel?context=create`, { method: 'POST' })
}
