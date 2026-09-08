<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { VIEW_NAMES } from '@/constants'
import { useSessionStore } from '@/stores/session'

/* 顶栏对齐旧版 L437-444 + updateChip(L1281-1292):chip 文案/配色逐字保留 */
const route = useRoute()
const session = useSessionStore()
const crumb = computed(() => VIEW_NAMES[route.name] || '')

const chipText = computed(() => session.apiMode
  ? (session.currentUser ? '在线 · 已连接后端' : '在线 · 未登录')
  : (session.booting ? '检测中…' : '离线 · 演示数据(点击重连)'))
const chipBg = computed(() => session.apiMode ? 'var(--green)' : 'var(--orange)')
const userChipText = computed(() => session.currentUser ? session.currentUser.display_name + ' · ' + session.roleName(session.currentUser.role) : '')

function onChipClick() {
  if (!session.apiMode) session.bootstrap()
}
</script>

<template>
  <div class="topbar">
    <span class="crumb">爱管理 <span aria-hidden="true">/</span> <b id="crumb-name">{{ crumb }}</b></span>
    <span style="display:flex;gap:8px;align-items:center">
      <span class="demo" id="mode-chip" :style="{ background: chipBg }" @click="onChipClick">{{ chipText }}</span>
      <span v-if="session.currentUser" class="demo" id="user-chip">{{ userChipText }}</span>
      <button v-if="session.currentUser" id="logout-btn" style="padding:3px 9px;font-size:12px" @click="session.logout()">退出</button>
    </span>
  </div>
</template>
