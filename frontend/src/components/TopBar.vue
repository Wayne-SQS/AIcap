<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { VIEW_NAMES } from '@/constants'
import { useToast } from '@/composables/useToast'

// 顶栏结构对齐旧版 index.html L437-444(#crumb-name/#mode-chip/#user-chip/#logout-btn)
const route = useRoute()
const crumb = computed(() => VIEW_NAMES[route.name] || '')

// PR-1 接入 session store 后接管 chip 的在线/离线状态与点击重连;当前为骨架占位
const chipText = '检测中…'
const chipBg = 'var(--orange)'
const { notify } = useToast()
function onChipClick() { notify('正在接入后端检测(PR-1 完成)') }
</script>

<template>
  <div class="topbar">
    <span class="crumb">爱管理 <span aria-hidden="true">/</span> <b id="crumb-name">{{ crumb }}</b></span>
    <span style="display:flex;gap:8px;align-items:center">
      <span class="demo" id="mode-chip" :style="{ background: chipBg }" @click="onChipClick">{{ chipText }}</span>
      <span class="demo" id="user-chip" style="display:none"></span>
      <button id="logout-btn" style="display:none;padding:3px 9px;font-size:12px">退出</button>
    </span>
  </div>
</template>
