<script setup>
import { computed } from 'vue'
import { useProjectStore } from '@/stores/project'

/* 变更记录面板:最近 50 条倒序,结构对齐旧版 L544 + renderLog(L993-997) */
const project = useProjectStore()
const rows = computed(() => project.log.slice().reverse())
</script>

<template>
  <details class="logpanel"><summary>📜 变更记录（本机）</summary>
    <div class="logbody" id="logbody">
      <div v-if="!rows.length" class="empty">暂无变更记录（操作后自动记录）</div>
      <div v-for="(l, i) in rows" :key="i" class="logrow">
        <time>{{ l.t }}</time><b :class="'t-' + l.type">{{ l.id }}</b><span>{{ l.detail }}</span>
      </div>
    </div>
  </details>
</template>
