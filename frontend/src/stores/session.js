import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { health, setAuthToken, getAuthToken } from '@/api/client'
import { useToast } from '@/composables/useToast'

/* 会话与在线/离线模式:对齐旧版 index.html L1262-1341 的
   apiMode/authToken/currentUser/bootstrap/doLogin/logout/updateChip/openLogin 全套语义 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    apiMode: false,
    authToken: getAuthToken(),
    currentUser: null,
    loginOpen: false,
    booting: false
  }),
  getters: {
    isViewer: s => s.apiMode && s.currentUser && s.currentUser.role === 'viewer',
    online: s => s.apiMode && !!s.currentUser
  },
  actions: {
    roleName(r) {
      return ({ admin: '管理员', owner: '负责人', member: '成员', viewer: '查看者' })[r] || r
    },
    openLogin() { this.loginOpen = true },
    handleUnauthorized() {
      // 对齐旧版 401 分支:清会话转离线 + toast + 弹登录
      this.apiMode = false
      this.authToken = null
      this.currentUser = null
      setAuthToken(null)
      const { notify } = useToast()
      notify('登录已过期，请重新登录')
      this.openLogin()
    },
    async bootstrap() {
      if (this.booting) return
      this.booting = true
      try {
        this.apiMode = await health()
        if (this.apiMode) {
          if (this.authToken) {
            try {
              this.currentUser = await authApi.me()
              const project = useProjectStoreSafe()
              if (project) await project.loadAll()
            } catch (e) {
              this.authToken = null
              setAuthToken(null)
              this.openLogin()
            }
          } else {
            this.openLogin()
          }
        } else {
          useProjectStoreSafe()?.restoreLocal()
        }
      } finally {
        this.booting = false
      }
    },
    async login(username, password) {
      const data = await authApi.login(username, password)
      this.authToken = data.access_token
      this.currentUser = data.user
      setAuthToken(this.authToken)
      this.apiMode = true
      this.loginOpen = false
      const project = useProjectStoreSafe()
      if (project) await project.loadAll()
      const { notify } = useToast()
      notify('欢迎，' + this.currentUser.display_name)
    },
    async logout() {
      this.authToken = null
      this.currentUser = null
      setAuthToken(null)
      this.apiMode = false
      useProjectStoreSafe()?.restoreLocal()
      const { notify } = useToast()
      notify('已退出登录，回到演示数据')
    },
    async goOffline() {
      // 登录弹窗的「离线演示模式」按钮
      this.loginOpen = false
      this.apiMode = false
      this.authToken = null
      this.currentUser = null
      setAuthToken(null)
      useProjectStoreSafe()?.restoreLocal()
      const { notify } = useToast()
      notify('已切换离线演示模式')
    },
    onLoginClosed() {
      // 对齐旧版 dialog close 处理:在线但未登录 → 回退离线
      if (this.apiMode && !this.currentUser) {
        this.apiMode = false
        useProjectStoreSafe()?.restoreLocal()
      }
    }
  }
})

/* 延迟引用 project store,避免模块级循环依赖 */
import { useProjectStore } from '@/stores/project'
function useProjectStoreSafe() {
  try { return useProjectStore() } catch (e) { return null }
}
