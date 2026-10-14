<script setup>
import { computed, onMounted } from 'vue'
import { useSupervisorStore } from '@/stores/supervisor'

/* 「本」的悬浮入口。纯像素:方块 + 硬边 + 零圆角 + 位移按压,不用任何现成 UI 库。
   灯色四档(离线/平台不可达/未配密钥/在线)—— 与 store.status 一一对应。
   为什么要有四种而不是两种:编排层挂了、平台挂了、没配密钥,这三种"不可用"
   用户要采取的动作完全不同(起服务 / 起后端 / 配 OpenAI key),混成一种等于没提示。 */
const sup = useSupervisorStore()

onMounted(() => sup.probe())

/* 「本」字 + 右下角状态灯。灯用 8×8 像素块,比 CSS 圆点更贴 pixel art */
const dotColor = computed(() => ({
  probing: 'var(--yellow)',
  offline: 'var(--gray)',
  'no-platform': 'var(--red)',
  'no-key': 'var(--orange)',
  online: 'var(--green-2)'
}[sup.status]))
</script>

<template>
  <button
    id="supervisor-avatar"
    class="sup-avatar"
    :class="['is-' + sup.status, { 'is-open': sup.open }]"
    type="button"
    :title="'本 · 项目主管 —— ' + sup.statusText"
    :aria-label="'本 · 项目主管,' + sup.statusText"
    :aria-expanded="sup.open"
    @click="sup.toggle()"
  >
    <span class="sup-face">本</span>
    <span class="sup-dot" :style="{ background: dotColor }" aria-hidden="true"></span>
    <span v-if="sup.running" class="sup-busy" aria-hidden="true"></span>
  </button>
</template>
