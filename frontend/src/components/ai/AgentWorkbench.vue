<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import MeetingView from '@/views/MeetingView.vue'
import ProfileAgentPanel from '@/components/ai/ProfileAgentPanel.vue'
import SubmitAgentPanel from '@/components/ai/SubmitAgentPanel.vue'
import PlanningAgentPanel from '@/components/ai/PlanningAgentPanel.vue'

/* 办公室右侧:三位同事的工作台 tab。
   - 译:整体复用 MeetingView —— 「返回审核中心」深链、meetingId 定位、
     提案聚焦(focusSource + MutationObserver)都长在那个视图里,拆开必丢。
   - 衡/绘:直接挂既有 Panel,业务零重写。
   只挂激活 tab:ProfileAgentPanel(513 行)/SubmitAgentPanel(358 行)/MeetingPanel(222 行)
   一次全挂会在进办公室时各自发一轮请求。 */
const tabs = [
  { id: 'meeting', colleague: '译', label: '会议工作区', component: MeetingView },
  { id: 'profile', colleague: '衡', label: '成员画像', component: ProfileAgentPanel },
  { id: 'submit', colleague: '衡', label: '提交分析', component: SubmitAgentPanel },
  { id: 'planning', colleague: '绘', label: '规划调整', component: PlanningAgentPanel }
]
const route = useRoute()
const router = useRouter()
const projects = useProjectsStore()
const active = ref('meeting')

/* 深链落位:审核中心「返回来源会议」与会议分析深链靠 query 传参,重定向进办公室
   后 query 落在 /office 上 —— 见到这些键就把会议 tab 顶到前面;?tab= 显式指定优先。 */
const meetingKeys = ['meetingId', 'analysisId', 'workflow', 'type', 'fromReview']
watch(() => route.query, q => {
  if (q.tab && tabs.some(t => t.id === q.tab)) { active.value = String(q.tab); return }
  if (meetingKeys.some(k => k in q)) active.value = 'meeting'
}, { immediate: true })

const activeTab = computed(() => tabs.find(t => t.id === active.value) || tabs[0])

/* 名单收敛:Commit Agent 已并入「衡」为提交分析;项目图生成器与 AI 对话演示
   是绘名下的工具/演示能力项,不再作为独立智能体出现(见智能体产品契约 §2.1)。 */
const extras = [
  { name: 'project-generator', label: '智能生成项目图', badge: '生成工具' },
  { name: 'ai-demo', label: 'AI 对话演示', badge: '静态演示 · 无真实模型' }
]
function openExtra(name) {
  const scoped = projects.currentProjectId && router.hasRoute(`project-${name}`)
  router.push({ name: scoped ? `project-${name}` : name, params: scoped ? { projectId: projects.currentProjectId } : undefined })
}

/* 办公室的像素工位卡与这里的 tab 是同一份状态:点工位 = 切 tab。
   只暴露一个 select,深链 query 的落位逻辑仍由下面的 watch 独占。 */
function select(id) {
  if (tabs.some(t => t.id === id)) active.value = id
}
defineExpose({ select })
</script>

<template>
  <div class="wb">
    <div class="wb-tabs" role="tablist" aria-label="同事工作台">
      <button
        v-for="t in tabs" :key="t.id" type="button" role="tab"
        :id="`wb-tab-${t.id}`" :aria-controls="`wb-panel-${t.id}`"
        :aria-selected="active === t.id" :tabindex="active === t.id ? 0 : -1"
        :class="{ 'is-active': active === t.id }"
        @click="active = t.id"
      ><span class="wb-colleague" aria-hidden="true">{{ t.colleague }}</span>{{ t.label }}</button>
    </div>
    <div :id="`wb-panel-${activeTab.id}`" class="wb-panel" role="tabpanel" :aria-labelledby="`wb-tab-${activeTab.id}`">
      <component :is="activeTab.component" :key="activeTab.id" />
    </div>
    <div class="wb-extra">
      <span class="wb-extra-label">更多能力</span>
      <button v-for="c in extras" :key="c.name" type="button" @click="openExtra(c.name)">
        {{ c.label }}<small>{{ c.badge }}</small>
      </button>
    </div>
  </div>
</template>
