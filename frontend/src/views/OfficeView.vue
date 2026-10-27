<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectsStore } from '@/stores/projects'
import { useReviewStore } from '@/stores/review'
import { useSupervisorStore } from '@/stores/supervisor'
import { loadReviewQueue, loadReviewQueueSummary } from '@/api/reviewQueue'
import SupervisorConversation from '@/components/SupervisorConversation.vue'
import AgentWorkbench from '@/components/ai/AgentWorkbench.vue'
import OfficeRoom from '@/components/office/OfficeRoom.vue'
import OfficeAvatar from '@/components/office/OfficeAvatar.vue'
import '@/styles/office.css'

const route = useRoute(), router = useRouter()
const meeting = useMeetingStore(), projects = useProjectsStore(), review = useReviewStore(), sup = useSupervisorStore()
const online = computed(() => meeting.online)
const projectName = computed(() => projects.currentProject?.name || '未选择项目')
const queueEntries = ref([]), recentMeeting = ref(null)
const summaryCounts = ref({ pendingCount: 0, executionCount: 0 })
const queueLoading = ref(false), queueError = ref('')
const pendingCount = computed(() => online.value ? summaryCounts.value.pendingCount : review.suggestions.filter(row => row.status === 'pending').length)
const latestProposal = computed(() => {
  if (online.value) return queueEntries.value[0] || null
  const item = review.suggestions[0]
  return item ? { source: 'offline', sourceLabel: '本机演示建议', proposalId: item.id, summaryText: item.note || item.affected, meetingTitle: '离线演示 · 本机数据' } : null
})
let queueVersion = 0
async function refreshQueueSummary() {
  if (!online.value || !projects.currentProjectId) return
  const version = ++queueVersion
  queueLoading.value = true
  queueError.value = ''
  try {
    let result
    try {
      const summary = await loadReviewQueueSummary()
      result = { entries: summary.latestEntry ? [summary.latestEntry] : [], recent: summary.recentMeeting, pending: summary.pendingCount, execution: summary.executionCount, errors: summary.errors }
    } catch (error) {
      if (error.message !== '审核队列摘要响应无效' && error.status !== 404) throw error
      await meeting.loadAll()
      const queue = await loadReviewQueue(meeting.meetings)
      const generic = meeting.suggestions.map(item => ({ source: 'general', sourceLabel: '会议建议', proposalId: item.id, summaryText: item.note || item.changes?.title, meetingTitle: item.meeting_title, createdAt: item.created_at }))
      result = { entries: [...queue.entries, ...generic].sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))), recent: meeting.meetings[0], pending: queue.entries.filter(row => row.status === 'pending').length + meeting.suggestions.filter(row => row.status === 'pending').length, execution: queue.entries.filter(row => row.status === 'approved' && row.executionStatus === 'not_started').length, errors: queue.errors }
    }
    if (version !== queueVersion) return
    queueEntries.value = result.entries
    recentMeeting.value = result.recent
    summaryCounts.value = { pendingCount: result.pending, executionCount: result.execution }
    if (result.errors.length) queueError.value = '部分提案详情读取失败，请刷新重试'
  } catch (error) {
    if (version === queueVersion) queueError.value = error.message || '读取队列失败'
  } finally { if (version === queueVersion) queueLoading.value = false }
}
watch([online, () => projects.currentProjectId], () => {
  ++queueVersion
  queueEntries.value = []; recentMeeting.value = null
  summaryCounts.value = { pendingCount: 0, executionCount: 0 }
  queueError.value = ''; queueLoading.value = false
  if (online.value) refreshQueueSummary()
}, { immediate: true })
function open(name, query) {
  const scoped = projects.currentProjectId && router.hasRoute(`project-${name}`)
  router.push({ name: scoped ? `project-${name}` : name, params: scoped ? { projectId: projects.currentProjectId } : undefined, query })
}
function continueProposal(row) {
  if (['general', 'offline'].includes(row.source)) return open('review', { suggestionId: row.proposalId })
  open('meetings', { meetingId: row.meetingId, type: row.source, analysisId: row.analysisId, fromReview: '1', status: row.status === 'pending' ? 'pending' : row.status === 'approved' && row.executionStatus === 'not_started' ? 'execution' : 'reviewed', source: row.source })
}

const paused = ref(false)
const people = [
  { id: 'ben', name: '本', role: '项目主管', hint: '有想法，来找我聊聊', color: '#a9bb83', description: '把需求交给本，由它查询项目、调度同事，并向你汇报。' },
  { id: 'meeting', name: '译', role: '会议同事', hint: '把讨论，整理成行动', color: '#a7c4c6', description: '整理会议、核对行动项，查看每条提案的原文依据。' },
  { id: 'profile', name: '衡', role: '画像同事', hint: '认真看见每一份贡献', color: '#d8b47c', description: '查看成员画像、提交活动和团队负载，区分事实与推断。' },
  { id: 'planning', name: '绘', role: '规划同事', hint: '让下一步，清晰一点', color: '#baacc8', description: '查看规划草案与影响分析，确认后再更新项目。' }
]
const desks = computed(() => people.map(person => ({
  ...person,
  // A reachable platform is not proof that an individual agent is running.
  busy: person.id === 'ben' && sup.running,
  state: person.id === 'ben' ? (sup.running ? 'busy' : sup.status) : online.value ? 'available' : 'offline',
  status: person.id === 'ben' ? (sup.running ? '正在处理' : sup.status === 'online' ? '可以聊聊' : sup.status === 'probing' ? '连接中' : '等待连接') : online.value ? '工作台可查看' : '离线演示'
})))
const visibleEvents = computed(() => sup.timeline.filter(item => item.label !== '开始'))
const latestEvent = computed(() => visibleEvents.value.at(-1))
const runGroups = computed(() => {
  const groups = []
  for (const item of visibleEvents.value) {
    const last = groups.at(-1)
    if (last && last.runId === item.runId) last.items.push(item)
    else groups.push({ runId: item.runId, items: [item] })
  }
  return groups
})

const drawer = ref(null), drawerKind = ref(''), workbench = ref(null)
const chatOpened = ref(false), workOpened = ref(false)
const activeTab = ref('meeting')
const selectedPerson = computed(() => people.find(person => person.id === drawerKind.value))
const drawerTitle = computed(() => drawerKind.value === 'activity' ? '办公室 · 过程记录' : `${selectedPerson.value?.name || ''} · ${selectedPerson.value?.role || ''}`)
const meetingKeys = ['meetingId', 'analysisId', 'workflow', 'type', 'fromReview']
let returnFocus = null
function openDesk(id) {
  if (!drawer.value?.open || !drawer.value.contains(document.activeElement)) returnFocus = document.activeElement
  const query = { ...route.query }
  delete query.panel; delete query.desk
  if (id === 'activity') { query.panel = 'activity'; delete query.tab }
  else if (id === 'ben') { query.desk = 'ben'; delete query.tab }
  else query.tab = id
  router.replace({ query })
}
async function syncDrawer(query) {
  let kind = ''
  if (query.panel === 'activity') kind = 'activity'
  else if (query.desk === 'ben') kind = 'ben'
  else if (['meeting', 'profile', 'submit', 'planning', 'efficiency'].includes(String(query.tab))) {
    activeTab.value = String(query.tab)
    kind = activeTab.value === 'submit' ? 'profile'
      : activeTab.value === 'efficiency' ? 'planning'
        : activeTab.value
  } else if (meetingKeys.some(key => key in query)) { activeTab.value = 'meeting'; kind = 'meeting' }
  drawerKind.value = kind
  if (kind === 'ben') chatOpened.value = true
  if (kind && !['ben', 'activity'].includes(kind)) workOpened.value = true
  await nextTick()
  if (kind && drawer.value?.isConnected && !drawer.value.open) drawer.value.showModal()
  else if (!kind && drawer.value?.open) drawer.value.close()
}
watch(() => route.query, syncDrawer, { immediate: true })
function closeDrawer() {
  drawer.value?.close()
  const query = { ...route.query }
  for (const key of ['desk', 'panel', 'tab', ...meetingKeys, 'proposalId', 'suggestionId', 'sourceType']) delete query[key]
  router.replace({ query })
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
}
function onBackdrop(event) {
  if (event.target !== drawer.value) return
  const bounds = drawer.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeDrawer()
}
function onWorkbenchSelect(tab) {
  if (route.query.tab !== tab) router.replace({ query: { ...route.query, tab } })
}
watch(() => sup.open, value => {
  if (!value) return
  sup.open = false
  openDesk('ben')
})
onMounted(() => { sup.probe(); sup.ensureStream(); if (sup.open) { sup.open = false; openDesk('ben') } })
onBeforeUnmount(() => { ++queueVersion; drawer.value?.close() })
</script>

<template>
  <section class="view immersive-office" id="view-office">
    <header class="office-header">
      <div class="office-heading"><div class="eyebrow">YOUR LITTLE TEAM, BIG IDEAS.</div><h1>AI 办公室<span class="office-room-number">ROOM 01</span></h1><p>四位同事，一起让想法发生。</p></div>
      <div class="office-header-actions"><span class="office-mode"><i :class="{ connected: online }"></i>{{ online ? '在线服务' : '离线演示' }}</span><button type="button" class="office-inbox" @click="open('review')"><span aria-hidden="true">▤</span> 提案信箱 <b>{{ queueLoading ? '…' : queueError ? '—' : pendingCount }}</b></button></div>
    </header>

    <div class="office-building">
      <div class="office-building-top"><span class="office-address"><i aria-hidden="true">⌂</i> {{ projectName }}<span class="office-separator">/</span>智能协作室</span><div class="office-room-tools"><span>{{ online ? '当前项目 · 真实数据' : '本机演示 · 非真实模型分析' }}</span><button type="button" :aria-pressed="paused" :aria-label="paused ? '开启动效' : '暂停动效'" @click="paused = !paused">{{ paused ? '▶' : 'Ⅱ' }}</button><button type="button" :disabled="queueLoading || !online" aria-label="刷新办公室摘要" @click="refreshQueueSummary">↻</button></div></div>
      <OfficeRoom :desks="desks" :active="drawerKind" :latest="latestEvent?.text || ''" :paused="paused" @select="openDesk" @activity="openDesk('activity')" />
      <div class="office-building-bottom"><span><i aria-hidden="true">✧</i> 点击同事或工位，看看他们正在做什么。</span><span class="office-team-count">1 位主管 <i>＋</i> 3 位同事</span></div>
    </div>

    <div class="office-bottom-grid">
      <button type="button" class="office-bottom-card office-talk-card" @click="openDesk('ben')"><OfficeAvatar who="ben" :size="36" :busy="sup.running" /><span><small>从这里开始</small><strong>有件事，想和本聊聊。</strong><span>查进度、看风险，或把一个想法交给团队。</span></span><i aria-hidden="true">↗</i></button>
      <button type="button" class="office-bottom-card office-latest-card" @click="latestProposal ? continueProposal(latestProposal) : openDesk('activity')"><span class="office-paper-icon" aria-hidden="true">▤</span><span><small>{{ latestProposal ? '最近产出' : '工作小记' }}</small><strong>{{ latestProposal?.meetingTitle || recentMeeting?.title || '好想法，值得被认真记下。' }}</strong><span>{{ latestProposal?.summaryText || latestEvent?.text || '对话、协作和产出，会在这里留下记录。' }}</span></span><i aria-hidden="true">↗</i></button>
    </div>
    <footer class="office-footer"><span>{{ online ? `${summaryCounts.executionCount} 条提案待执行` : '离线演示数据保存在本机' }}</span><button v-if="latestProposal" type="button" @click="continueProposal(latestProposal)">继续查看最近提案 ↗</button></footer>
    <p v-if="queueError" class="office-error" role="alert">{{ queueError }} <button type="button" :disabled="queueLoading" @click="refreshQueueSummary">重新加载摘要</button></p>

    <dialog ref="drawer" class="office-drawer" :class="{ 'is-workbench': workOpened && !['ben', 'activity'].includes(drawerKind) }" aria-labelledby="office-drawer-title" @cancel.prevent="closeDrawer" @click="onBackdrop">
      <header class="office-drawer-heading"><OfficeAvatar v-if="selectedPerson" :who="selectedPerson.id" :size="36" /><div><small>{{ drawerKind === 'activity' ? 'THE TEAM JOURNAL' : 'MEET YOUR TEAMMATE' }}</small><h2 id="office-drawer-title">{{ drawerTitle }}</h2></div><button type="button" class="office-close" aria-label="关闭工作抽屉" autofocus @click="closeDrawer">×</button></header>
      <div class="office-drawer-switch" aria-label="切换同事"><button v-for="person in people" :key="person.id" type="button" :class="{ selected: drawerKind === person.id }" :aria-pressed="drawerKind === person.id" @click="openDesk(person.id)">{{ person.name }}<span>{{ person.role }}</span></button><button type="button" :class="{ selected: drawerKind === 'activity' }" :aria-pressed="drawerKind === 'activity'" @click="openDesk('activity')">▤<span>过程记录</span></button></div>
      <p v-if="selectedPerson" class="office-drawer-description">{{ selectedPerson.description }}</p>
      <section v-if="chatOpened" v-show="drawerKind === 'ben'" class="office-drawer-chat" aria-label="主管对话"><div class="office-chat-toolbar"><span><i :class="`status-${sup.status}`"></i>{{ sup.statusText }}</span><button type="button" @click="sup.resetSession()">＋ 新话题</button></div><SupervisorConversation /></section>
      <section v-show="drawerKind === 'activity'" class="office-drawer-journal" aria-label="过程时间轴"><div class="office-journal-title"><strong>每一步，都有迹可循。</strong><span>{{ visibleEvents.length }} 条记录</span></div><div v-if="!runGroups.length" class="office-journal-empty"><span aria-hidden="true">▧</span><h3>白板还是空的。</h3><p>向本提一个问题。团队查了什么、找了谁、完成了什么，都会写在这里。</p><button type="button" @click="openDesk('ben')">找本聊聊 ↗</button></div><section v-for="(group, index) in runGroups" :key="group.runId || index" class="office-journal-run"><h3>第 {{ index + 1 }} 轮协作 <small>{{ group.items.length }} 步</small></h3><ol class="sup-steps"><li v-for="item in group.items" :key="item.seq" :class="`is-${item.tone}`"><span class="sup-stepdot" aria-hidden="true"></span><span class="sup-steplabel">{{ item.label }}</span><span class="sup-steptext">{{ item.text }}</span><span v-if="item.detail" class="sup-stepdetail">{{ item.detail }}</span></li></ol></section></section>
      <section v-if="workOpened" v-show="!['ben', 'activity'].includes(drawerKind)" class="office-drawer-work" aria-label="同事工作台"><AgentWorkbench :key="projects.currentProjectId" ref="workbench" :initial-tab="activeTab" @select="onWorkbenchSelect" /></section>
    </dialog>
  </section>
</template>
