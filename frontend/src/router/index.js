import { createRouter, createWebHashHistory } from 'vue-router'

// hash 路由:构建产物可用任意静态服务器直接伺服,无需 SPA fallback;
// 路由 name 沿用旧版 go(v) 的 view key,E2E 兼容桥 window.go 即 router.push({name})
const routes = [
  { path: '/', redirect: { name: 'overview' } },
  { path: '/overview', name: 'overview', component: () => import('@/views/OverviewView.vue') },
  { path: '/pool', name: 'pool', component: () => import('@/views/PoolView.vue') },
  { path: '/board', name: 'board', component: () => import('@/views/BoardView.vue') },
  { path: '/gantt', name: 'gantt', component: () => import('@/views/GanttView.vue') },
  { path: '/members', name: 'members', component: () => import('@/views/MembersView.vue') },
  { path: '/profiles', name: 'profiles', component: () => import('@/views/ProfilesView.vue') },
  { path: '/uml', name: 'uml', component: () => import('@/views/UmlView.vue') },
  { path: '/ai', name: 'ai', component: () => import('@/views/AiView.vue') },
  { path: '/meetings', name: 'meetings', component: () => import('@/views/MeetingView.vue') },
  { path: '/submit-agent', name: 'submit-agent', component: () => import('@/views/SubmitAgentView.vue') },
  { path: '/profile-agent', name: 'profile-agent', component: () => import('@/views/ProfileAgentView.vue') },
  { path: '/ai-demo', name: 'ai-demo', component: () => import('@/views/AiDemoView.vue') },
  { path: '/drawing-agent', name: 'drawing-agent', component: () => import('@/views/DrawingAgentView.vue') },
  { path: '/project-generator', name: 'project-generator', component: () => import('@/views/ProjectGeneratorView.vue') },
  { path: '/review', name: 'review', component: () => import('@/views/ReviewView.vue') }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 对应旧版 go() 的 window.scrollTo({top:0})
router.afterEach(() => {
  window.scrollTo({ top: 0 })
})

export default router
