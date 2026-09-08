/* E2E 兼容桥:旧版 spec 通过 page.evaluate 直接调用页面全局函数
   (api/loadAll/renderAll/go/curView),Vue 版保留同名 window 入口 */
import { api } from '@/api/client'
import { useProjectStore } from '@/stores/project'

export function installWindowBridge({ router, app }) {
  window.api = api
  window.go = v => router.push({ name: v })
  // Vue 响应式渲染下 renderAll 为 no-op,仅为兼容 spec 调用序列
  window.renderAll = () => {}
  window.loadAll = async () => {
    const project = useProjectStore()
    await project.loadAll()
  }
  Object.defineProperty(window, 'curView', {
    get: () => router.currentRoute.value.name
  })
}
