import { reactive } from 'vue'

/* 全局 toast:对齐旧版 notify()(#toast 单例 + 3s 自动隐藏) */
const state = reactive({ text: '', show: false })
let timer = null

export function useToast() {
  function notify(s) {
    state.text = s
    state.show = true
    clearTimeout(timer)
    timer = setTimeout(() => { state.show = false }, 3000)
  }
  return { state, notify }
}
