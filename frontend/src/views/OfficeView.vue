<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectsStore } from '@/stores/projects'
import { useReviewStore } from '@/stores/review'
import { useSupervisorStore } from '@/stores/supervisor'
import { loadReviewQueue, loadReviewQueueSummary } from '@/api/reviewQueue'
import SupervisorConversation from '@/components/SupervisorConversation.vue'
import AgentWorkbench from '@/components/ai/AgentWorkbench.vue'
import OfficeAvatar from '@/components/office/OfficeAvatar.vue'

/* AI 办公室:一间像素风格的办公室。
   门牌 = 服务状态;场景 = 四张工位(本 / 译 / 衡 / 绘);
   屋里的三栏各司其职 ——
     前台   = 主管「本」的常驻对话(与右下角浮窗共用同一份 store 与同一条 SSE 流)
     白板   = 过程时间轴(全量 sup.timeline,按 run 分组,能翻回每一轮"找了谁、干了什么")
     工位区 = 三位同事的工作台(译/衡/绘,见 AgentWorkbench;点击场景里的工位卡即切换)
   顶部便签(服务模式/待审核数/最近提案)自 AI 工作台并入,原 AiView 不再有路由入口。 */
const route = useRoute()
const router = useRouter()
const meeting = useMeetingStore()
const projects = useProjectsStore()
const review = useReviewStore()
const sup = useSupervisorStore()
const online = computed(() => meeting.online)
const queueEntries = ref([])
const recentMeeting = ref(null)
const summaryCounts = ref({ pendingCount: 0, executionCount: 0 })
const indexedSummary = ref(false)
const queueLoading = ref(false)
const queueError = ref('')
const pendingCount = computed(() => online.value
  ? summaryCounts.value.pendingCount
  : review.suggestions.filter(row => row.status === 'pending').length)
const executionCount = computed(() => summaryCounts.value.executionCount)
const latestProposal = computed(() => {
  if (!online.value) {
    const suggestion = review.suggestions[0]
    return suggestion ? { source: 'offline', sourceLabel: '本机演示建议', proposalId: suggestion.id, summaryText: suggestion.note || suggestion.affected, meetingTitle: '离线演示 · 本机数据' } : null
  }
  if (indexedSummary.value) return queueEntries.value[0] || null
  const generic = meeting.suggestions.map(suggestion => ({
    source: 'general', sourceLabel: suggestion.origin === 'manual' ? '手动会议建议' : '通用会议 Agent',
    proposalId: suggestion.id, meetingId: suggestion.meeting_id, meetingTitle: suggestion.meeting_title,
    summaryText: suggestion.changes?.title ? `新增需求：${suggestion.changes.title}` : suggestion.note || '通用会议建议',
    createdAt: suggestion.created_at || ''
  }))
  return [...queueEntries.value, ...generic].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))[0] || null
})
const projectName = computed(() => projects.currentProject?.name || '未选择项目')

watch(() => meeting.online, value => { if (value) refreshQueueSummary() }, { immediate: true })
watch(() => projects.currentProjectId, (next, previous) => {
  if (next === previous) return
  meeting.meetings = []
  meeting.suggestions = []
  meeting.selected = ''
  queueEntries.value = []
  recentMeeting.value = null
  summaryCounts.value = { pendingCount: 0, executionCount: 0 }
  if (next && meeting.online) refreshQueueSummary()
})

async function refreshQueueSummary() {
  queueLoading.value = true
  queueError.value = ''
  try {
    try {
      const result = await loadReviewQueueSummary()
      summaryCounts.value = { pendingCount: result.pendingCount, executionCount: result.executionCount }
      queueEntries.value = result.latestEntry ? [result.latestEntry] : []
      recentMeeting.value = result.recentMeeting
      indexedSummary.value = true
      if (result.errors.length) queueError.value = '最近提案详情读取失败，请刷新重试'
    } catch (error) {
      if (error.message !== '审核队列摘要响应无效' && error.status !== 404) throw error
      await meeting.loadAll()
      const result = await loadReviewQueue(meeting.meetings)
      queueEntries.value = result.entries
      recentMeeting.value = meeting.meetings[0] || null
      summaryCounts.value = {
        pendingCount: result.entries.filter(row => row.status === 'pending').length + meeting.suggestions.filter(row => row.status === 'pending').length,
        executionCount: result.entries.filter(row => row.status === 'approved' && row.executionStatus === 'not_started').length
      }
      indexedSummary.value = false
      if (result.errors.length) queueError.value = `部分来源读取失败（${result.errors.length} 项），数字可能不完整`
    }
  } catch (error) { queueError.value = error.message || '读取队列失败' }
  finally { queueLoading.value = false }
}
function open(name, query = undefined) {
  const scoped = projects.currentProjectId && router.hasRoute(`project-${name}`)
  router.push({ name: scoped ? `project-${name}` : name, params: scoped ? { projectId: projects.currentProjectId } : undefined, query })
}
function continueProposal(row) {
  if (row.source === 'general' || row.source === 'offline') {
    open('review', { suggestionId: row.proposalId })
    return
  }
  const status = row.status === 'pending' ? 'pending' : row.status === 'approved' && row.executionStatus === 'not_started' ? 'execution' : 'reviewed'
  open('meetings', {
    meetingId: row.meetingId, type: row.source, analysisId: row.analysisId,
    fromReview: '1', status, source: row.source
  })
}

/* ---- 办公室工位 ---- */
const workbench = ref(null)
const chatEl = ref(null)
const activeDesk = ref('meeting')
const desks = [
  { id: 'ben', name: '本', role: 'SUPERVISOR', hint: '唯一对话对象' },
  { id: 'meeting', name: '译', role: 'MEETING', hint: '会议 → 行动' },
  { id: 'profile', name: '衡', role: 'PROFILE', hint: '谁做了什么' },
  { id: 'planning', name: '绘', role: 'PLANNING', hint: '数据 → 图形' }
]
/* 工位灯:本反映主管连接四态 + 忙碌;同事沿用会议域在线态。
   四态各自有颜色,和 sup.status 的语义一一对应(见 stores/supervisor.js)。 */
function deskState(id) {
  if (id === 'ben') {
    if (sup.running) return 'busy'
    return sup.status
  }
  return online.value ? 'online' : 'offline'
}
function focusDesk(id) {
  if (id === 'ben') {
    chatEl.value?.querySelector('.sup-foot input')?.focus()
    return
  }
  activeDesk.value = id
  workbench.value?.select?.(id)
}
/* 深链落位:带 ?tab= 或会议深链(meetingId/analysisId/…)进来时,工位卡也同步高亮,
   否则会出现"右栏已经切到画像页、场景里的会议工位还亮着"的错位。
   切 tab 本身仍由 AgentWorkbench 内部的 watch 负责,这里只同步场景高亮。 */
const meetingKeys = ['meetingId', 'analysisId', 'workflow', 'type', 'fromReview']
watch(() => route.query, q => {
  const tab = q.tab ? String(q.tab) : ''
  if (['meeting', 'profile', 'submit', 'planning'].includes(tab)) {
    activeDesk.value = tab === 'submit' ? 'profile' : tab
  } else if (meetingKeys.some(k => k in q)) {
    activeDesk.value = 'meeting'
  }
}, { immediate: true })

/* ---- 白板:过程时间轴(全量 timeline 按连续 runId 分组) ---- */
const feedEl = ref(null)
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
watch(() => sup.timeline.length, async () => {
  await nextTick()
  if (feedEl.value) feedEl.value.scrollTop = feedEl.value.scrollHeight
})
</script>

<template>
  <section class="view office-view" id="view-office">
    <!-- 门牌:办公室的身份与状态 -->
    <header class="of-sign">
      <div class="of-sign-main">
        <span class="of-sign-room" aria-hidden="true">01</span>
        <div class="of-sign-text">
          <div class="eyebrow">AI OFFICE / 智能协作</div>
          <h1>AI 办公室</h1>
        </div>
      </div>
      <div class="of-sign-side">
        <span class="of-sign-project" :title="projectName">{{ projectName }}</span>
        <span class="of-lamp" :class="`is-${sup.status}`"><i aria-hidden="true"></i>{{ sup.statusText }}</span>
        <button class="of-sign-btn" type="button" :disabled="!online || queueLoading" @click="refreshQueueSummary">刷新</button>
      </div>
    </header>

    <p class="of-lede">和主管「本」说需求，它调度三位同事 —— 译（会议）、衡（画像）、绘（规划）。过程可查，产出先审后行。</p>

    <!-- 墙上的三张便签:服务模式 / 待处理 / 最近提案 -->
    <div class="of-sum">
      <article class="of-note">
        <span class="of-note-pin" aria-hidden="true"></span>
        <h4>当前服务模式</h4>
        <b>{{ online ? '在线服务' : '离线演示' }}</b>
        <p class="small">{{ online ? '会议与分析操作使用已连接的服务。' : '当前使用本地演示数据；不会形成服务器审核或真实模型结论。' }}</p>
      </article>
      <article class="of-note">
        <span class="of-note-pin" aria-hidden="true"></span>
        <h4>待处理摘要</h4>
        <b>{{ queueLoading ? '正在统计…' : `${pendingCount} 条待审核` }}</b>
        <p v-if="online" class="small">{{ executionCount }} 条已批准待执行。{{ queueError ? ` ${queueError}。` : '审核与执行仍由来源流程处理。' }}</p>
        <p v-else class="small">离线演示中的待审建议仅保存在本机，不会进入服务器队列。</p>
        <div class="of-note-actions">
          <button @click="open('review')">打开审核中心</button>
          <button v-if="online" type="button" :disabled="queueLoading" @click="refreshQueueSummary">刷新摘要</button>
        </div>
      </article>
      <article class="of-note">
        <span class="of-note-pin" aria-hidden="true"></span>
        <h4>最近会议 / 提案</h4>
        <b>{{ latestProposal?.meetingTitle || recentMeeting?.title || meeting.selectedMeeting?.title || meeting.meetings[0]?.title || '尚无已选会议' }}</b>
        <p class="small">{{ latestProposal ? `${latestProposal.sourceLabel} · ${latestProposal.summaryText}` : recentMeeting || meeting.selectedMeeting ? '继续查看会议输入、分析与提案。' : '在线模式下可创建或选择会议。' }}</p>
        <div class="of-note-actions">
          <button @click="open('meetings')">进入会议工作区</button>
          <button v-if="latestProposal" type="button" @click="continueProposal(latestProposal)">继续查看最近提案</button>
        </div>
      </article>
    </div>

    <!-- 办公室场景:一面墙 + 一张地板,四张工位 -->
    <section class="of-scene" aria-label="办公室工位">
      <div class="of-scene-wall" aria-hidden="true">
        <span class="of-window">
          <i class="of-cloud"></i>
          <i class="of-cloud is-far"></i>
        </span>
        <span class="of-clock"><i></i></span>
        <span class="of-wall-note">TODAY · 把会议变成任务</span>
        <svg class="of-plant" viewBox="0 0 24 24">
          <rect x="4" y="16" width="16" height="8" fill="#c9a77c" />
          <rect x="6" y="7" width="4" height="9" fill="#7fae5a" />
          <rect x="10" y="4" width="4" height="12" fill="#a8cf76" />
          <rect x="14" y="8" width="4" height="8" fill="#5f8f45" />
        </svg>
        <span class="of-door"></span>
      </div>
      <div class="of-scene-floor">
        <button
          v-for="d in desks" :key="d.id" type="button" class="of-desk"
          :class="{ 'is-active': activeDesk === d.id }" :aria-pressed="activeDesk === d.id"
          @click="focusDesk(d.id)"
        >
          <span class="of-desk-lamp" :class="`is-${deskState(d.id)}`" aria-hidden="true"></span>
          <span class="of-desk-screen" aria-hidden="true"><i></i><i></i><i></i></span>
          <OfficeAvatar :who="d.id" :size="52" />
          <span class="of-desk-name">{{ d.name }}</span>
          <span class="of-desk-role">{{ d.role }}</span>
          <span class="of-desk-hint">{{ d.hint }}</span>
        </button>
      </div>
    </section>

    <!-- 屋里的三栏:前台 / 白板 / 同事工位 -->
    <div class="of-grid">
      <section ref="chatEl" class="office-chat of-frontdesk" aria-label="主管对话">
        <header class="sup-head">
          <span class="sup-headmark" aria-hidden="true">本</span>
          <div class="sup-headtext">
            <strong>本 · 前台</strong>
            <small>{{ sup.statusText }}</small>
          </div>
          <button class="sup-icon" type="button" title="换个话题(新建会话)" @click="sup.resetSession()">＋</button>
        </header>
        <SupervisorConversation />
        <span class="of-frontdesk-tag" aria-hidden="true">RECEPTION / 前台</span>
      </section>

      <section class="office-feed of-whiteboard" aria-label="过程时间轴">
        <header class="office-feed-head">
          <b>白板 · 过程记录</b>
          <small>{{ sup.timeline.length }} 条事件</small>
        </header>
        <div ref="feedEl" class="office-feed-body">
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

      <aside class="of-work" aria-label="同事工作台">
        <AgentWorkbench ref="workbench" />
      </aside>
    </div>
  </section>
</template>
