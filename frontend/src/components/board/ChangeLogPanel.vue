<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'

/* 变更记录面板:最近 50 条,最新在前(story store 的 log 统一为「旧 → 新」,这里反向渲染)
   数据来源随模式变化:在线=服务器审计日志(story_logs),离线=本机记录 */
const project = useProjectStore()
const session = useSessionStore()

const fromServer = computed(() => session.apiMode && project.serverSync)
const rows = computed(() => project.log.slice().reverse())
const panelTitle = computed(() => (fromServer.value ? '📜 变更记录（服务器审计日志）' : '📜 变更记录（本机）'))
const panelHint = computed(() => (fromServer.value
  ? '来自服务器审计日志（GET /api/stories/logs），展示最近 50 条，最新在前'
  : '来自本机记录，展示最近 50 条，最新在前'))
</script>

<template>
  <details class="logpanel"><summary :title="panelHint">{{ panelTitle }}</summary>
    <div class="logbody" id="logbody">
      <div v-if="!rows.length" class="empty">暂无变更记录（操作后自动记录）</div>
      <div v-for="(l, i) in rows" :key="i" class="logrow">
        <time>{{ l.t }}</time><b :class="'t-' + l.type">{{ l.id }}</b><span>{{ l.detail }}</span>
      </div>
    </div>
  </details>
</template>
