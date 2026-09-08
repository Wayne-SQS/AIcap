<script setup>
import { ref, watch, nextTick } from 'vue'
import { useSessionStore } from '@/stores/session'
import { ACCOUNTS } from '@/data/seed'
import { useToast } from '@/composables/useToast'

/* 对齐旧版 <dialog id="login">(L672-689) + openLogin/doLogin(L1323-1341)
   用原生 showModal() 保持 [open] 属性与 ::backdrop 模态语义(E2E 断言依赖) */
const session = useSessionStore()
const username = ref('')
const password = ref('123456')
const dialogEl = ref(null)
const { notify } = useToast()

watch(() => session.loginOpen, async open => {
  await nextTick()
  const d = dialogEl.value
  if (!d) return
  if (open && !d.open) d.showModal()
  else if (!open && d.open) d.close()
})

async function submit() {
  try {
    await session.login(username.value.trim(), password.value)
  } catch (err) {
    notify('登录失败：' + err.message)
  }
}
function pickAccount(name) {
  username.value = name
  password.value = '123456'
}
function onClosed() {
  session.loginOpen = false
  session.onLoginClosed()
}
</script>

<template>
  <dialog id="login" ref="dialogEl" @close="onClosed">
    <form id="login-form" @submit.prevent="submit">
      <header>
        <h2>登录 · 爱管理</h2>
        <button type="button" id="login-close" aria-label="关闭" @click="session.loginOpen = false">×</button>
      </header>
      <div class="formbody">
        <p class="small" style="margin:0">已连接后端 · 请选择成员登录(演示密码 123456)</p>
        <div class="login-accounts" id="login-accounts">
          <button v-for="n in ACCOUNTS" :key="n" type="button" class="acct" :data-acct="n" @click="pickAccount(n)">{{ n }}</button>
        </div>
        <label class="field">账号<input id="login-user" v-model="username" required placeholder="成员1"></label>
        <label class="field">密码<input id="login-pass" v-model="password" type="password" required></label>
        <div class="actions">
          <button type="button" id="login-offline" @click="session.goOffline()">离线演示模式</button>
          <button type="submit" class="primary">登录</button>
        </div>
      </div>
    </form>
  </dialog>
</template>
