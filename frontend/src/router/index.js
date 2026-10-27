import { createRouter, createWebHashHistory } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'

/* 智能协作收敛为两处入口:AI 办公室 + 提案审核中心(2026-10-18)。
   ai-demo / project-generator 降级为办公室右侧的能力项,但路由保留
   (project-generator 还承担 /projects/new 的复用)。 */
const workspaceViews = [
  { name: 'overview', label: '项目总览', component: () => import('@/views/OverviewView.vue') },
  { name: 'insights', label: '项目分析中心', component: () => import('@/views/InsightsView.vue') },
  { name: 'pool', label: '需求池', component: () => import('@/views/PoolView.vue') },
  { name: 'board', label: '用户故事看板', component: () => import('@/views/BoardView.vue') },
  { name: 'gantt', label: '甘特图', component: () => import('@/views/GanttView.vue') },
  { name: 'members', label: '成员任务图', component: () => import('@/views/MembersView.vue') },
  { name: 'profiles', label: '成员画像', component: () => import('@/views/ProfilesView.vue') },
  { name: 'uml', label: 'UML 图', component: () => import('@/views/UmlView.vue') },
  { name: 'office', label: 'AI 办公室', component: () => import('@/views/OfficeView.vue') },
  /* 2026-10-27：原「智能体调用中心」(AgentsView) 与 AI 办公室重复 —— 会议/画像/提交/
     规划四块与办公室右栏的同事工作台是同一批面板，效率分析已归「项目分析中心」。
     页面已删，路由名留着做重定向（见 `agentsRedirect`），旧书签/审核中心深链不会 404。 */
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

/* `/agents` 是已删除的「智能体调用中心」的旧地址（2026-10-27）。它用 `?agent=` 选面板，
   所以不能简单并入 toOffice：把 agent 翻成办公室工作台的 `?tab=`（效率分析现在也是
   工作台的一页）。原 query（runId / analysisId 等）原样保留。 */
const AGENT_TAB = {
  meeting: 'meeting',
  profile: 'profile',
  submit: 'submit',
  commit: 'submit',
  planning: 'planning',
  efficiency: 'efficiency'
}
const agentsRedirect = to => {
  const query = { ...to.query }
  const agent = String(query.agent || '')
  delete query.agent
  const scoped = String(to.name || '').startsWith('project-')
  const tab = AGENT_TAB[agent]
  if (tab) query.tab = tab
  return scoped
    ? { name: 'project-office', params: to.params, query }
    : { name: 'office', query }
}

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
  ]),
  ...['agents'].flatMap(name => [
    {
      path: `/projects/:projectId/${name}`,
      name: `project-${name}`,
      redirect: agentsRedirect,
      meta: { legacyName: name, projectScoped: true }
    },
    { path: `/${name}`, name, redirect: agentsRedirect }
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
router.afterEach((to, from) => { if (to.path !== from.path) window.scrollTo({ top: 0 }) })
export default router
