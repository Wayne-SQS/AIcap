import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'

/* 只读角色(viewer)写拦截:对齐旧版 isViewer/viewerDeny(L1278-1280)
   仅在线且角色为 viewer 时生效,不影响离线与其他角色 */
export function usePermissionGuard() {
  const session = useSessionStore()
  const { notify } = useToast()

  function viewerDeny(action) {
    notify('只读角色（查看者）不能' + (action || '执行写操作') + '，如需修改请联系管理员')
  }
  function guard(action) {
    // 返回 true 表示被拦截
    if (session.isViewer) { viewerDeny(action); return true }
    return false
  }
  return { session, viewerDeny, guard }
}
