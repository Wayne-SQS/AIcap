<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectsStore } from '@/stores/projects'
import { useReviewStore } from '@/stores/review'
import { useSupervisorStore } from '@/stores/supervisor'
import { loadReviewQueue, loadReviewQueueSummary } from '@/api/reviewQueue'
import SupervisorConversation from '@/components/SupervisorConversation.vue'
import AgentWorkbench from '@/components/ai/AgentWorkbench.vue'

/* AI 办公室(2026-10-19 改版:一行门牌 + 两栏)。
   结构与《智能体产品契约》§1 的办公室隐喻一一对应:
     门牌行 = 办公室身份 + 本的状态灯 + 待审徽标(直达审核中心)
     左栏   = 主管「本」的常驻对话(与右下角浮窗共用同一份 store 与同一条 SSE 流)
              + 白板抽屉(全量 timeline,按 run 分组;对话气泡内嵌的是每轮过程,
                抽屉只服务"翻旧账",默认收起)
     右栏   = 三位同事的工位条(译/衡/绘,工位卡即 tab,单一状态源)+ 工作面板
   原三张便签压缩进状态行;像素场景墙(窗/云/钟/植物/门)已删,像素味由
   工位卡与头像保留。派活闭环:白板看过程 → 面板看产出 → 门牌徽标去审核。 */
const router = useRouter()
const meeting = useMeetingStore()
const projects = useProjectsStore()
const review = useReviewStore()
const sup = useSupervisorStore()
const online = computed(() => meeting.online)
const queueLoading = ref(false)
const queueError = ref('')
const summaryCounts = ref({ pendingCount: 0, executionCount: 0 })
const pendingCount = computed(() => online.value
  ? summaryCounts.value.pendingCount
  : review.suggestions.filter(row => row.status === 'pending').length)
const executionCount = computed(() => summaryCounts.value.executionCount)
const projectName = computed(() => projects.currentProject?.name || '未选择项目')
const hasProject = computed(() => !!projects.currentProjectId)

/* 状态行:按上下文给一句话(离线演示 / 未选项目 / 在线摘要) */
const statusline = computed(() => {
  if (!online.value) return { tone: 'offline', text: '离线演示中的待审建议仅保存在本机，不会进入服务器队列。' }
  if (!hasProject.value) return { tone: 'warn', text: '请先在左上角选择项目 —— 同事的工作台与产出按项目隔离。' }
  const parts = [`${executionCount.value} 条已批准待执行`]
  if (queueError.value) parts.push(queueError.value)
  else parts.push('审核与执行仍由来源流程处理')
  return { tone: 'ok', text: parts.join(' · ') }
})

watch(() => meeting.online, value => { if (value) refreshQueueSummary() }, { immediate: true })
watch(() => projects.currentProjectId, (next, previous) => {
  if (next === previous) return
  meeting.meetings = []
  meeting.suggestions = []
  meeting.selected = ''
  summaryCounts.value = { pendingCount: 0, executionCount: 0 }
  if (next && meeting.online) refreshQueueSummary()
})

async function refreshQueueSummary() {
  queueLoading.value = true
  queueError.value = ''
  try {
    const result = await loadReviewQueueSummary()
    summaryCounts.value = { pendingCount: result.pendingCount, executionCount: result.executionCount }
    if (result.errors.length) queueError.value = '最近提案详情读取失败，请刷新重试'
  } catch (error) {
    /* 摘要接口不可用时退回逐会议聚合,数字仍可用(旧路径保留) */
    if (error.message !== '审核队列摘要响应无效' && error.status !== 404) {
      queueError.value = error.message || '读取队列失败'
      queueLoading.value = false
      return
    }
    try {
      await meeting.loadAll()
      const result = await loadReviewQueue(meeting.meetings)
      summaryCounts.value = {
        pendingCount: result.entries.filter(row => row.status === 'pending').length + meeting.suggestions.filter(row => row.status === 'pending').length,
        executionCount: result.entries.filter(row => row.status === 'approved' && row.executionStatus === 'not_started').length
      }
      if (result.errors.length) queueError.value = `部分来源读取失败（${result.errors.length} 项），数字可能不完整`
    } catch (fallbackError) {
      queueError.value = fallbackError.message || '读取队列失败'
    }
  } finally { queueLoading.value = false }
}
function open(name) {
  const scoped = projects.currentProjectId && router.hasRoute(`project-${name}`)
  router.push({ name: scoped ? `project-${name}` : name, params: scoped ? { projectId: projects.currentProjectId } : undefined })
}

/* ---- 白板抽屉 ---- */
const workbench = ref(null)
const feedEl = ref(null)
const feedOpen = ref(false)
const runGroups = computed(() => {
  const groups = []
  for (const item of sup.timeline) {
    const last = groups[groups.length - 1]
    if (last && last.runId === item.runId) { last.items.push(item); continue }
    groups.push({ runId: item.runId, items: [item] })
  }
  return groups.map(g => ({
    ...g,
    short: g.runId ? String(g.runId).slice(0, 8) : '本轮',
    steps: g.items.filter(t => t.label !== '开始')
  })).filter(g => g.steps.length)
})
/* 有新的过程事件时自动展开抽屉:派活的经过要看得见,不用用户记得去开 */
watch(() => sup.timeline.length, async (next, previous) => {
  if (next > (previous || 0)) feedOpen.value = true
  await nextTick()
  if (feedOpen.value && feedEl.value) feedEl.value.scrollTop = feedEl.value.scrollHeight
})
function refreshAll() {
  refreshQueueSummary()
  workbench.value?.refresh?.()
}
</script>

<template>
  <section class="view office-view" id="view-office">
    <!-- 门牌行:身份 + 本的状态 + 待审徽标(直达审核中心) -->
    <header class="of-sign">
      <div class="of-sign-main">
        <div class="of-sign-text">
          <div class="eyebrow">AI OFFICE / 智能协作</div>
          <h1>AI 办公室</h1>
        </div>
      </div>
      <div class="of-sign-side">
        <span class="of-sign-project" :title="projectName">{{ projectName }}</span>
        <span class="of-lamp" :class="`is-${sup.status}`" :title="sup.statusText"><i aria-hidden="true"></i>{{ sup.statusText }}</span>
        <button class="of-review-link" type="button" :disabled="queueLoading" @click="open('review')">待审 {{ pendingCount }}</button>
        <button class="of-sign-btn" type="button" :disabled="!online || queueLoading" @click="refreshAll">刷新</button>
      </div>
    </header>

    <p class="of-statusline" :class="`is-${statusline.tone}`">{{ statusline.text }}</p>

    <!-- 两栏:左 = 本(对话 + 白板抽屉),右 = 同事工位条 + 工作面板 -->
    <div class="of-grid">
      <div class="of-left">
        <section class="office-chat of-frontdesk" aria-label="主管对话">
          <header class="sup-head">
            <span class="sup-headmark" aria-hidden="true">本</span>
            <div class="sup-headtext">
              <strong>本 · 主管</strong>
              <small>{{ sup.statusText }}</small>
            </div>
            <button class="sup-icon" type="button" title="换个话题(新建会话)" @click="sup.resetSession()">＋</button>
          </header>
          <SupervisorConversation />
        </section>

        <section class="office-feed of-whiteboard" :class="{ 'is-open': feedOpen }" aria-label="过程时间轴">
          <button class="office-feed-head" type="button" :aria-expanded="feedOpen" @click="feedOpen = !feedOpen">
            <b>白板 · 过程记录</b>
            <small>{{ sup.timeline.length }} 条事件 · {{ feedOpen ? '收起 ▴' : '展开 ▾' }}</small>
          </button>
          <div v-show="feedOpen" ref="feedEl" class="office-feed-body">
            <p v-if="!runGroups.length" class="empty">白板还是空的。向「本」提问或让它派活后，这里会写下每一步 —— 找了谁、查了什么、回了什么。</p>
            <template v-for="(g, gi) in runGroups" :key="g.runId || gi">
              <div class="office-feed-run"><span>RUN {{ g.short }}</span><small>{{ g.steps.length }} 步</small></div>
              <ol class="sup-steps">
                <li v-for="s in g.steps" :key="s.seq" :class="'is-' + s.tone">
                  <span class="sup-stepdot" aria-hidden="true"></span>
                  <span class="sup-steplabel">{{ s.label }}</span>
                  <span class="sup-steptext">{{ s.text }}</span>
                  <span v-if="s.detail" class="sup-stepdetail">{{ s.detail }}</span>
                </li>
              </ol>
            </template>
          </div>
        </section>
      </div>

      <aside class="of-work" aria-label="同事工作台">
        <AgentWorkbench ref="workbench" />
      </aside>
    </div>
  </section>
</template>
