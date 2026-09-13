<script setup>
import { computed, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import AppSidebar from '@/components/AppSidebar.vue'
import TopBar from '@/components/TopBar.vue'
import ToastBox from '@/components/ToastBox.vue'
import LoginDialog from '@/components/LoginDialog.vue'

const project = useProjectStore()

/* 一致性自检:数据变化后跑一次 checkConsistency(仅控制台告警,不改数据)
   对齐 legacy 新版 renderAll() → checkConsistency() 的常驻校验;
   用轻量签名而不是深监听,避免每次渲染都全量比对 */
const dataSignature = computed(() => [
  project.stories.length,
  project.tasks.length,
  project.members.length,
  project.tasks.map(t => `${t.id}:${t.w[0]}-${t.w[1]}:${t.owner}:${t.dependsOn || ''}:${t.card || ''}`).join('|')
].join('#'))
watch(dataSignature, () => project.checkConsistency())
</script>

<template>
  <div class="shell">
    <AppSidebar />
    <main class="main">
      <TopBar />
      <router-view />
    </main>
  </div>
  <ToastBox />
  <LoginDialog />
</template>
