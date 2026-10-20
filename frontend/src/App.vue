<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useToast } from '@/composables/useToast'
import AppSidebar from '@/components/AppSidebar.vue'
import TopBar from '@/components/TopBar.vue'
import ToastBox from '@/components/ToastBox.vue'
import LoginDialog from '@/components/LoginDialog.vue'
import SupervisorAvatar from '@/components/SupervisorAvatar.vue'
import SupervisorChat from '@/components/SupervisorChat.vue'

const project = useProjectStore()
const route = useRoute()
const isOffice = computed(() => route.meta.legacyName === 'office' || route.name === 'office')
const { notify } = useToast()
const reloading = ref(false)
async function reloadProject() {
  reloading.value = true
  try { await project.loadAll() }
  catch (error) { notify('重新加载失败：' + error.message) }
  finally { reloading.value = false }
}

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
  <div class="shell" :class="{ 'is-office': isOffice }">
    <AppSidebar />
    <main class="main">
      <TopBar />
      <div v-if="project.logError" class="project-load-warning" role="status">
        <span>变更记录加载失败：{{ project.logError }}。用户故事与任务等项目数据已加载。</span>
        <button type="button" :disabled="reloading" @click="reloadProject">{{ reloading ? '重试中…' : '重新加载' }}</button>
      </div>
      <router-view />
    </main>
  </div>
  <ToastBox />
  <LoginDialog />
  <!-- 「本」是全站唯一 AI 入口:其它入口都保留着,但这里是"进办公室"的那扇门 -->
  <SupervisorAvatar />
  <SupervisorChat />
</template>

<style scoped>
.project-load-warning{margin:16px 28px 0;padding:12px 16px;border:2px solid var(--ink);background:var(--yellow);display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;font-size:13px}
</style>
