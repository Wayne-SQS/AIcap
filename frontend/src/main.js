import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/client'
import { useSessionStore } from './stores/session'
import { installWindowBridge } from './compat/windowBridge'
import './styles/index.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 401 → session store 处理(清会话/转离线/toast/弹登录),解耦自 api/client
const session = useSessionStore()
setUnauthorizedHandler(() => session.handleUnauthorized())

app.mount('#app')

installWindowBridge({ router, app })
session.bootstrap()
