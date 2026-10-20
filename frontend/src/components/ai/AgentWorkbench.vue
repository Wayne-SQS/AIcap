<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectsStore } from '@/stores/projects'
import { api } from '@/api/client'
import MeetingView from '@/views/MeetingView.vue'
import ProfileAgentPanel from '@/components/ai/ProfileAgentPanel.vue'
import SubmitAgentPanel from '@/components/ai/SubmitAgentPanel.vue'
import PlanningAgentPanel from '@/components/ai/PlanningAgentPanel.vue'
import OfficeAvatar from '@/components/office/OfficeAvatar.vue'

/* 办公室右栏:三位同事的工位条 + 工作面板(契约 §2.1:3 同事 + 1 主管)。
   - 工位卡即 tab:译/衡/绘,单一状态源,不再有"场景工位卡 vs 工作 tab"两套。
   - 衡的两个能力(画像分析 / 提交分析)收进衡面板的子 tab —— 是一个同事的两件
     事,不是两个入口。
   - 绘的工具(智能生成项目图 / AI 对话演示)与产出(甘特 / 成员任务图 / UML)
     收进绘面板底部,不再与三位同事并列出现。
   - 只挂激活 tab:ProfileAgentPanel / SubmitAgentPanel 一次全挂会在进办公室时
     各自发一轮请求(MeetingView 经 embedded 隐藏页头,内容直达)。
   - 工位灯:数据源是 Java /api/health 的 workers 快照(公开端点),不再借用
     meeting.online 的演示模式语义 —— 译的分析由平台同步执行无独立 worker,
     衡看 profile-agent,绘看 planning-agent + project-generation。 */
const route = useRoute()
const router = useRouter()
const meeting = useMeetingStore()
const projects = useProjectsStore()

const tabs = [
  { id: 'meeting', colleague: '译', label: '会议', hint: '转写 → 行动' },
  { id: 'profile', colleague: '衡', label: '画像', hint: '谁做了什么' },
  { id: 'planning', colleague: '绘', label: '规划', hint: '数据 → 图形' }
]
const active = ref('meeting')
const profileTab = ref('analysis')
const activeTab = computed(() => tabs.find(t => t.id === active.value) || tabs[0])

/* 深链落位(全仓唯一一处):审核中心「返回来源会议」等深链靠 query 传参,
   重定向进办公室后 query 落在 /office 上。?tab= 显式指定优先(兼容旧值
   submit → 衡的提交分析子页),见到会议类键就把译顶到前面。 */
const meetingKeys = ['meetingId', 'analysisId', 'workflow', 'type', 'fromReview']
watch(() => route.query, q => {
  const tab = q.tab ? String(q.tab) : ''
  if (['meeting', 'profile', 'submit', 'planning'].includes(tab)) {
    if (tab === 'submit') { active.value = 'profile'; profileTab.value = 'submit' }
    else { active.value = tab; if (tab === 'profile') profileTab.value = 'analysis' }
    return
  }
  if (meetingKeys.some(k => k in q)) active.value = 'meeting'
}, { immediate: true })

/* ---- 工位灯:/api/health workers 快照 ---- */
const platform = ref({ state: 'probing', workers: {} })
async function probePlatform() {
  try {
    const data = await api('/api/health', { timeoutMs: 8000 })
    platform.value = { state: data.status === 'ok' && data.db !== false ? 'ok' : 'down', workers: data.workers || {} }
  } catch (e) {
    platform.value = { state: 'down', workers: {} }
  }
}
onMounted(probePlatform)
watch(() => meeting.online, value => { if (value) probePlatform() })
function refresh() { probePlatform() }
defineExpose({ refresh })

const lampText = { online: '在线', warn: '待命', down: '不可达', demo: '离线演示', probing: '探活中' }
function workerLamp(keys) {
  if (!meeting.online) return 'demo'
  if (platform.value.state === 'probing') return 'probing'
  if (platform.value.state === 'down') return 'down'
  const list = keys.map(k => platform.value.workers[k]).filter(Boolean)
  if (list.some(w => w.active)) return 'online'
  return 'warn'
}
function lamp(id) {
  if (id === 'meeting') {
    if (!meeting.online) return 'demo'
    if (platform.value.state === 'probing') return 'probing'
    return platform.value.state === 'ok' ? 'online' : 'down'
  }
  if (id === 'profile') return workerLamp(['profile-agent'])
  return workerLamp(['planning-agent', 'project-generation'])
}

/* ---- 绘的工具与产出(项目空间视图,按项目作用域跳转) ---- */
function openScoped(name) {
  if (!projects.currentProjectId) return router.push({ name: 'projects' })
  router.push({ name: `project-${name}`, params: { projectId: projects.currentProjectId } })
}
const extras = [
  { name: 'project-generator', label: '智能生成项目图', badge: '生成工具' },
  { name: 'ai-demo', label: 'AI 对话演示', badge: '静态演示 · 无真实模型' }
]
const outputs = [
  { name: 'gantt', label: '甘特图' },
  { name: 'members', label: '成员任务图' },
  { name: 'uml', label: 'UML 图' }
]
</script>

<template>
  <div class="wb">
    <!-- 工位条:工位卡即 tab -->
    <div class="wb-tabs of-strip" role="tablist" aria-label="同事工位">
      <button
        v-for="t in tabs" :key="t.id" type="button" role="tab"
        :id="`wb-tab-${t.id}`" :aria-controls="`wb-panel-${t.id}`"
        :aria-label="`${t.colleague} · ${t.label}`"
        :aria-selected="active === t.id" :tabindex="active === t.id ? 0 : -1"
        class="of-desk" :class="{ 'is-active': active === t.id }"
        @click="active = t.id"
      >
        <span class="of-desk-lamp" :class="`is-${lamp(t.id)}`" :title="lampText[lamp(t.id)]" aria-hidden="true"></span>
        <OfficeAvatar :who="t.id" :size="46" />
        <span class="of-desk-text">
          <span class="of-desk-name">{{ t.colleague }}<small>{{ t.label }}</small></span>
          <span class="of-desk-hint">{{ t.hint }}</span>
        </span>
      </button>
    </div>

    <!-- 衡的两件工作:画像分析 / 提交分析 -->
    <div v-if="active === 'profile'" class="wb-subtabs" role="tablist" aria-label="衡的工作">
      <button
        type="button" role="tab" :aria-selected="profileTab === 'analysis'"
        :class="{ 'is-active': profileTab === 'analysis' }" @click="profileTab = 'analysis'"
      >画像分析</button>
      <button
        type="button" role="tab" :aria-selected="profileTab === 'submit'"
        :class="{ 'is-active': profileTab === 'submit' }" @click="profileTab = 'submit'"
      >提交分析</button>
    </div>

    <div :id="`wb-panel-${activeTab.id}`" class="wb-panel" role="tabpanel" :aria-labelledby="`wb-tab-${activeTab.id}`">
      <MeetingView v-if="active === 'meeting'" embedded />
      <ProfileAgentPanel v-else-if="active === 'profile' && profileTab === 'analysis'" />
      <SubmitAgentPanel v-else-if="active === 'profile' && profileTab === 'submit'" />
      <template v-else>
        <PlanningAgentPanel />
        <div class="wb-extra">
          <span class="wb-extra-label">绘的工具</span>
          <button v-for="c in extras" :key="c.name" type="button" @click="openScoped(c.name)">
            {{ c.label }}<small>{{ c.badge }}</small>
          </button>
          <span class="wb-extra-label">产出查看</span>
          <button v-for="o in outputs" :key="o.name" type="button" class="is-plain" @click="openScoped(o.name)">
            {{ o.label }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
