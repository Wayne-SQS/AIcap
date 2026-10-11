<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useReviewStore } from '@/stores/review'
import { loadReviewQueue, loadReviewQueueSummary } from '@/api/reviewQueue'

const router = useRouter()
const meeting = useMeetingStore()
const review = useReviewStore()
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
watch(() => meeting.online, value => { if (value) refreshQueueSummary() }, { immediate: true })

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
function continueProposal(row) {
  if (row.source === 'general' || row.source === 'offline') {
    router.push({ name: 'review', query: { suggestionId: row.proposalId } })
    return
  }
  const status = row.status === 'pending' ? 'pending' : row.status === 'approved' && row.executionStatus === 'not_started' ? 'execution' : 'reviewed'
  router.push({ name: 'meetings', query: {
    meetingId: row.meetingId, type: row.source, analysisId: row.analysisId,
    fromReview: '1', status, source: row.source
  } })
}

const agents = [
  { title: '会议工作区', name: '会议智能体', desc: '录入文本或语音，选择会议分析类型并跟进提案。', input: '会议文本、录音与已保存会议', result: '分析结果和待审核提案', impact: '审核通过后仍需单独执行', route: 'meetings', badge: '在线服务', kind: '真实流程' },
  { title: '任务提交分析', name: 'Commit Agent', desc: '同步团队开发活动，查看提交风险与任务分析。', input: 'GitHub 活动与项目任务', result: '活动摘要、风险及分析建议', impact: '分析本身不直接修改项目数据', route: 'submit-agent', badge: '服务配置相关', kind: '独立工作区' },
  { title: '成员画像分析', name: '画像智能体', desc: '运行成员能力画像分析，查看数据依据与建议。', input: '成员画像与已同步活动', result: '能力分析和风险线索', impact: '分析不直接更改任务分配', route: 'profile-agent', badge: '服务配置相关', kind: '独立工作区' },
  { title: 'Planning Agent · 项目规划', name: '项目规划 Agent', desc: '用自然语言调整项目规划，查看影响后确认同步；与会议中的 Sprint Planning 分析区分。', input: '项目规划指令与当前项目数据', result: '规划修改草案和影响分析', impact: '确认后同步更新项目数据', route: 'drawing-agent', badge: '在线服务 · 确认后写入', kind: '项目规划' },
  { title: '智能生成项目图', name: '项目图生成器', desc: '生成项目图示；此入口与绘图工具分开呈现。', input: '项目上下文', result: '项目图', impact: '请在页面确认具体保存行为', route: 'project-generator', badge: '生成工具', kind: '独立工作区' },
  { title: '对话演示', name: 'AI 对话演示', desc: '体验固定示例问答，不连接真实模型。', input: '示例问题', result: '静态演示回复', impact: '不修改项目数据', route: 'ai-demo', badge: '静态演示', kind: '只读演示' }
]
function open(name) { router.push({ name }) }
</script>

<template>
  <section class="view" id="view-ai">
    <div class="hero"><div><div class="eyebrow">AI WORKSPACE / 智能能力目录</div><h1>AI 工作台</h1><p>选择要处理的工作。每项能力都标明输入、产物、数据影响和服务类型。</p></div></div>
    <div class="sum-grid">
      <article class="sum-card"><h4>当前服务模式</h4><b>{{ online ? '在线服务' : '离线演示' }}</b><p class="small">{{ online ? '会议与分析操作使用已连接的服务。' : '当前使用本地演示数据；不会形成服务器审核或真实模型结论。' }}</p></article>
      <article class="sum-card"><h4>待处理摘要</h4><b>{{ queueLoading ? '正在统计…' : `${pendingCount} 条待审核` }}</b><p v-if="online" class="small">{{ executionCount }} 条已批准待执行。{{ queueError ? ` ${queueError}。` : '审核与执行仍由来源流程处理。' }}</p><p v-else class="small">离线演示中的待审建议仅保存在本机，不会进入服务器队列。</p><div class="workbench-actions"><button @click="open('review')">打开审核中心</button><button v-if="online" type="button" :disabled="queueLoading" @click="refreshQueueSummary">刷新摘要</button></div></article>
      <article class="sum-card"><h4>最近会议 / 提案</h4><b>{{ latestProposal?.meetingTitle || recentMeeting?.title || meeting.selectedMeeting?.title || meeting.meetings[0]?.title || '尚无已选会议' }}</b><p class="small">{{ latestProposal ? `${latestProposal.sourceLabel} · ${latestProposal.summaryText}` : recentMeeting || meeting.selectedMeeting ? '继续查看会议输入、分析与提案。' : '在线模式下可创建或选择会议。' }}</p><div class="workbench-actions"><button @click="open('meetings')">进入会议工作区</button><button v-if="latestProposal" type="button" @click="continueProposal(latestProposal)">继续查看最近提案</button></div></article>
    </div>
    <div class="h-sec">智能体与工具</div>
    <div class="sum-grid agent-catalog">
      <article v-for="agent in agents" :key="agent.title" class="sum-card agent-card">
        <div class="sug-head"><span class="eyebrow">{{ agent.kind }}</span><span class="sug-status">{{ agent.route === 'meetings' ? `当前：${online ? '在线服务' : '离线演示'}` : agent.badge }}</span></div>
        <h3>{{ agent.title }}</h3><p>{{ agent.desc }}</p>
        <dl><dt>输入</dt><dd>{{ agent.input }}</dd><dt>结果</dt><dd>{{ agent.result }}</dd><dt>数据影响</dt><dd>{{ agent.impact }}</dd><dt>权限</dt><dd>页面会按当前角色禁用不可用操作；后端仍执行权限校验。</dd></dl>
        <button class="primary" @click="open(agent.route)">进入工作区</button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.agent-card h3{margin:10px 0 6px}.agent-card p{min-height:44px}.agent-card dl{display:grid;grid-template-columns:72px 1fr;gap:7px 10px;margin:14px 0;font-size:13px}.agent-card dt{font-weight:700;color:var(--muted,#667085)}.agent-card dd{margin:0}.agent-catalog{align-items:stretch}.agent-card{display:flex;flex-direction:column}.agent-card button{margin-top:auto;align-self:flex-start}.workbench-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
</style>
