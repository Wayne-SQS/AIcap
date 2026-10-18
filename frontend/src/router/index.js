import { createRouter, createWebHashHistory } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'

/* 智能协作收敛为两处入口:AI 办公室 + 提案审核中心(2026-10-18)。
   ai-demo / project-generator 降级为办公室右侧的能力项,但路由保留
   (project-generator 还承担 /projects/new 的复用)。 */
const workspaceViews = [
  { name: 'office', label: 'AI 办公室', component: () => import('@/views/OfficeView.vue') },
  { name: 'review', label: '提案审核中心', component: () => import('@/views/ReviewView.vue') },
  { name: 'ai-demo', label: 'AI 对话演示', component: () => import('@/views/AiDemoView.vue') },
  { name: 'project-generator', label: '智能生成项目图', component: () => import('@/views/ProjectGeneratorView.vue') }
]

/* 旧智能协作入口 → 办公室。redirect 必须**原样透传 params + query**:
   审核中心「返回来源会议」深链(fromReview/status/meetingId/type/analysisId)
   全靠 query 传,丢了深链就断;项目作用域那套还要带 projectId。 */
const legacyOfficeRedirects = ['ai', 'meetings', 'submit-agent', 'profile-agent', 'drawing-agent']
const toOffice = to => String(to.name || '').startsWith('project-')
  ? { name: 'project-office', params: to.params, query: to.query }
  : { name: 'office', query: to.query }

const routes = [
  { path: '/', redirect: { name: 'projects' } },
  { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
  { path: '/projects/new', name: 'project-new', component: () => import('@/views/ProjectGeneratorView.vue'), meta: { generatorContext: 'create', label: '新建项目' } },
  { path: '/projects/:projectId', redirect: to => ({ name: 'project-overview', params: { projectId: to.params.projectId } }) },
  ...workspaceViews.map(({ name, label, component }) => ({
    path: `/projects/:projectId/${name}`,
    name: `project-${name}`,
    component,
    meta: { legacyName: name, label, projectScoped: true }
  })),
  ...workspaceViews.map(({ name, component }) => ({
    path: `/${name}`,
    name,
    component
  })),
  ...legacyOfficeRedirects.flatMap(name => [
    {
      path: `/projects/:projectId/${name}`,
      name: `project-${name}`,
      redirect: toOffice,
      meta: { legacyName: name, projectScoped: true }
    },
    { path: `/${name}`, name, redirect: toOffice }
  ])
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach(to => {
  if (!to.meta.projectScoped) return
  const projectId = String(to.params.projectId || '').trim().toUpperCase()
  if (!projectId) return { name: 'projects' }
  const projects = useProjectsStore()
  projects.currentProjectId = projectId
  projects.persistSelection()
})
router.afterEach(() => { window.scrollTo({ top: 0 }) })
export default router
