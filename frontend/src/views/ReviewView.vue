<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useReviewStore } from '@/stores/review'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectStore } from '@/stores/project'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import ReviewEditDialog from '@/components/review/ReviewEditDialog.vue'
import { loadReviewQueue, loadReviewQueuePage, matchesQueueStatus } from '@/api/reviewQueue'

/* AI 建议审核中心:离线=本地演示建议(decideSug);在线=服务器建议三键审核
   对齐旧版 renderReview(L1160-1168) + meeting-review.js renderReview(L88-113)
   + meeting-improvements.js 修改后采纳(L27-41) */
const review = useReviewStore()
const meeting = useMeetingStore()
const project = useProjectStore()
const router = useRouter()
const route = useRoute()
const { notify } = useToast()
const { guard } = usePermissionGuard()
const editRef = ref(null)
const sourceQueueRows = ref([])
const queueErrors = ref([])
const queueLoading = ref(false)
const pagedQueue = ref(false)
const nextCursor = ref(null)
const queueFilter = ref(String(route.query.status || 'pending'))
const queueSource = ref(String(route.query.source || 'all'))
const queueSearch = ref(String(route.query.search || ''))
const queueFrom = ref(String(route.query.fromDate || ''))
const queueTo = ref(String(route.query.toDate || ''))

watch(() => route.query.suggestionId, async id => {
  if (!id) return
  await nextTick()
  document.querySelector(`[data-meeting-suggestion="${CSS.escape(String(id))}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  document.querySelector(`[data-sug="${CSS.escape(String(id))}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}, { immediate: true })

const statusMap = { pending: '待审核', approved: '已采纳', rejected: '已拒绝', modified: '已修改' }
const serverStatusName = { pending: '待审核', approved: '已采纳', rejected: '已拒绝' }
const online = computed(() => meeting.online)
const decisions = computed(() => review.decisions.slice().reverse())
const serverDecisions = computed(() => meeting.suggestions.filter(s => s.reviewed_at))
const queueRows = computed(() => [
  ...sourceQueueRows.value,
  ...(pagedQueue.value ? [] : meeting.suggestions.map(s => ({
    id: `general:${s.id}`, source: 'general', sourceLabel: s.origin === 'manual' ? '手动会议建议' : '通用会议 Agent',
    meetingId: s.meeting_id, meetingTitle: s.meeting_title, analysisId: '', proposalId: s.id,
    createdAt: s.created_at || '', status: s.status, executionStatus: s.pool_item_id ? 'succeeded' : s.status === 'approved' ? 'succeeded' : 'not_started',
    proposal: { original_proposal: { reason: s.note, evidence: [{ quote: s.evidence }], changes: s.changes }, decision: s.status, reason: s.reason, reviewed_by: s.reviewed_by, reviewed_at: s.reviewed_at },
    execution: s.pool_item_id ? { result_id: s.pool_item_id, executed_by: s.executed_by, executed_at: s.executed_at } : null,
    summaryText: s.changes?.title ? `新增需求：${s.changes.title} · ${s.changes.priority || ''}` : s.note || '查看来源分析了解详情',
    flowStage: s.status === 'pending' ? '分析完成 → 待审核' : s.status === 'rejected' ? '分析完成 → 已拒绝（未执行）' : '分析完成 → 已批准 → 执行成功',
    reviewer: s.reviewed_by, reviewedAt: s.reviewed_at, reviewReason: s.reason,
    executor: s.executed_by, executedAt: s.executed_at, resultId: s.pool_item_id
  })))
])
const allQueueSources = [
  ['general', '通用会议建议 / 手动建议'], ['daily', 'Daily'], ['planning', 'Sprint Planning'],
  ['review', 'Sprint Review'], ['retro', 'Sprint Retro'], ['refinement', 'Backlog Refinement'],
  ['assignment', 'Assignment 分配建议']
]
const queueSources = computed(() => pagedQueue.value ? allQueueSources : [...new Map(queueRows.value.map(row => [row.source, row.sourceLabel])).entries()])
const visibleQueue = computed(() => pagedQueue.value ? queueRows.value : queueRows.value.filter(row => {
  const statusMatch = matchesQueueStatus(row, queueFilter.value)
  const time = Date.parse(row.createdAt)
  const from = queueFrom.value ? Date.parse(`${queueFrom.value}T00:00:00`) : -Infinity
  const to = queueTo.value ? Date.parse(`${queueTo.value}T23:59:59.999`) : Infinity
  const searchMatch = !queueSearch.value || `${row.meetingTitle} ${row.sourceLabel} ${row.proposalId}`.toLowerCase().includes(queueSearch.value.trim().toLowerCase())
  return statusMatch && (queueSource.value === 'all' || row.source === queueSource.value) && searchMatch && (!Number.isFinite(time) || (time >= from && time <= to))
}))

/* bootstrap 完成后 online 才为 true:watch 而非 onMounted,避免挂载早于会话初始化漏加载 */
watch(online, v => {
  if (v) refresh().catch(() => { /* 可手动刷新 */ })
}, { immediate: true })

async function refresh() {
  queueLoading.value = true
  try {
    await meeting.loadAll(); await project.loadAll()
    await loadFirstPage()
  }
  catch (err) { notify('刷新失败：' + err.message) }
  finally { queueLoading.value = false }
}

async function refreshQueue() {
  queueLoading.value = true
  try {
    await loadFirstPage()
  } catch (err) { notify('刷新跨流程提案失败：' + err.message) }
  finally { queueLoading.value = false }
}

const currentFilters = () => ({ status: queueFilter.value, source: queueSource.value,
  search: queueSearch.value.trim(), fromDate: queueFrom.value, toDate: queueTo.value })
let loadSequence = 0
async function loadFirstPage() {
  const sequence = ++loadSequence
  let result, paged = true
  try { result = await loadReviewQueuePage(currentFilters()) }
  catch (error) {
    // Older Java installations do not provide the index yet. Keep their existing queue behavior.
    if (error.message !== '审核队列分页响应无效' && error.status !== 404) throw error
    result = await loadReviewQueue(meeting.meetings)
    paged = false
  }
  if (sequence !== loadSequence) return
  pagedQueue.value = paged
  sourceQueueRows.value = result.entries
  queueErrors.value = result.errors
  nextCursor.value = paged ? result.nextCursor : null
}

async function loadMore() {
  if (!pagedQueue.value || !nextCursor.value || queueLoading.value) return
  const sequence = loadSequence
  queueLoading.value = true
  try {
    const result = await loadReviewQueuePage(currentFilters(), nextCursor.value)
    if (sequence !== loadSequence) return
    const seen = new Set(sourceQueueRows.value.map(row => row.id))
    sourceQueueRows.value.push(...result.entries.filter(row => !seen.has(row.id)))
    queueErrors.value.push(...result.errors)
    nextCursor.value = result.nextCursor
  } catch (error) { notify('加载下一页失败：' + error.message) }
  finally { queueLoading.value = false }
}

let filterTimer
watch([queueFilter, queueSource, queueSearch, queueFrom, queueTo], () => {
  if (!pagedQueue.value) return
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => refreshQueue(), 250)
})

function openSource(row) {
  if (row.source === 'general') {
    meeting.select(row.meetingId)
    router.push({ name: 'meetings', query: {
      meetingId: row.meetingId, fromReview: '1', sourceType: 'general', suggestionId: row.proposalId,
      status: queueFilter.value, source: queueSource.value, search: queueSearch.value,
      fromDate: queueFrom.value, toDate: queueTo.value
    } })
    return
  }
  meeting.select(row.meetingId)
  router.push({ name: 'meetings', query: {
    meetingId: row.meetingId, type: row.source, analysisId: row.analysisId, fromReview: '1',
    status: queueFilter.value, source: queueSource.value, search: queueSearch.value,
    fromDate: queueFrom.value, toDate: queueTo.value
  } })
}

function executionLabel(row) {
  const state = row.executionStatus
  if (state === 'succeeded') return `执行成功${row.resultId ? ` · 结果 #${row.resultId}` : ''}`
  if (state === 'not_started') return row.status === 'approved' ? '待执行' : row.status === 'rejected' ? '未执行（已拒绝）' : '尚未批准'
  if (state === 'not_applicable' || state === 'not_needed') return row.status === 'rejected' ? '未执行（已拒绝）' : '无需执行'
  if (state === 'failed') return '执行失败 · 请刷新来源状态并从来源流程重试'
  if (state === 'conflict') return '执行冲突 · 请刷新来源状态后确认当前业务数据'
  return state || '未知执行状态'
}

/* 离线演示:三键决策 + prompt 理由(只读角色拦截,对齐 legacy 新版 decideSug 的 isViewer) */
function decide(id, act) {
  if (guard('审核 AI 建议')) return
  const reason = prompt('决策理由（留空则记为已确认）：')
  if (reason === null) return
  const actTxt = review.decide(id, act, reason)
  if (actTxt) notify(`${id} 已${actTxt}`)
}

/* 在线:采纳/拒绝(prompt 理由,取消则不审核) */
async function serverDecide(id, decision) {
  if (meeting.busy.includes(id)) return
  if (guard('审核 AI 建议')) return
  const reason = prompt('审核理由（可留空；取消则不审核）：')
  if (reason === null) return
  meeting.busy.push(id)
  try {
    const result = await meeting.review(id, decision, reason)
    notify(result.status === 'approved' ? '已采纳并创建需求 ' + result.pool_item_id : '已拒绝，未改变业务数据')
    try { await meeting.loadAll(); await project.loadAll(); await refreshQueue() } catch (err) { notify('审核已保存，但刷新失败：' + err.message) }
  } catch (err) { notify('审核未确认，请刷新核对后重试：' + err.message) }
  finally {
    meeting.busy = meeting.busy.filter(x => x !== id)
  }
}

/* 在线:修改后采纳(弹窗表单) */
async function serverEdit(id) {
  if (meeting.busy.includes(id)) return
  if (guard('修改并采纳 AI 建议')) return
  const s = meeting.suggestions.find(x => x.id === id)
  if (!s) return
  const values = await editRef.value.open(s)
  if (!values) return
  meeting.busy.push(id)
  try {
    await meeting.review(id, null, values.reason, values.changes)
    notify('修改已采纳，原始建议已保留；创建需求 ' + '（见需求池）')
    try { await meeting.loadAll(); await project.loadAll(); await refreshQueue() } catch (err) { notify('审核已保存，刷新失败：' + err.message) }
  } catch (err) { notify('审核未确认，请刷新核对后重试：' + err.message) }
  finally {
    meeting.busy = meeting.busy.filter(x => x !== id)
    editRef.value?.finish()
  }
}

</script>

<template>
  <section class="view" id="view-review">
    <div class="hero">
      <div>
        <div class="eyebrow">AI REVIEW / 人在回路</div>
        <h1>提案审核中心</h1>
        <p>集中查看通用会议建议、Daily、Sprint Planning、Sprint Review、Sprint Retro、Backlog Refinement 和 Assignment 提案。审核与执行仍由各自来源流程处理。</p>
      </div>
    </div>

    <!-- 离线:本地演示建议 -->
    <div v-if="!online" id="sug-list">
      <div v-for="s in review.suggestions" :key="s.id" class="sug-card">
        <div class="sug-head">
          <span class="sug-agent" :class="s.kind">{{ s.kind === 'meeting' ? '🎙' : '🚀' }} {{ s.agent }}</span>
          <span class="sug-status" :class="s.status">{{ statusMap[s.status] || '待审核' }}</span>
          <span class="small mono" style="margin-left:auto">{{ s.id }} · {{ s.time }}</span>
        </div>
        <div class="sug-row"><b>证据</b><span>{{ s.evidence }}</span></div>
        <div class="sug-row"><b>影响对象</b><span>{{ s.affected }}</span></div>
        <div class="sug-row"><b>建议说明</b><span>{{ s.note || '' }}</span></div>
        <div class="sug-diff">
          <template v-for="(c, i) in s.change || []" :key="i"><br v-if="i" /><span :class="c.d ? 'del' : 'add'">{{ c.d ? '− ' : '＋ ' }}{{ c.t }}</span></template>
        </div>
        <div v-if="s.status === 'pending'" class="sug-acts">
          <button class="approve" :data-sug="s.id" data-act="approved" @click="decide(s.id, 'approved')">✓ 采纳</button>
          <button :data-sug="s.id" data-act="modified" @click="decide(s.id, 'modified')">✎ 修改后采纳</button>
          <button class="reject" :data-sug="s.id" data-act="rejected" @click="decide(s.id, 'rejected')">✕ 拒绝</button>
        </div>
      </div>
      <div v-if="!review.suggestions.length" class="empty">暂无 AI 建议</div>
    </div>

    <!-- 在线:服务器建议 -->
    <div v-else id="sug-list">
      <section class="review-queue" aria-label="跨流程提案队列">
        <div class="panel-heading"><div><h2>跨流程提案队列</h2><p class="small">汇总通用建议、各会议分析与 Assignment 的服务器记录；审核和执行沿用原接口，队列不复制决策。</p></div><button id="review-refresh" type="button" :disabled="queueLoading" @click="refresh">{{ queueLoading ? '刷新中…' : '刷新全部提案' }}</button></div>
        <div class="queue-filters"><label>处理状态<select v-model="queueFilter"><option value="pending">待审核</option><option value="reviewed">已审核</option><option value="execution">待执行 / 执行结果</option><option value="all">全部状态</option></select></label><label>来源流程<select v-model="queueSource"><option value="all">全部来源</option><option v-for="[key,label] in queueSources" :key="key" :value="key">{{ label }}</option></select></label><label>会议 / 提案<input v-model="queueSearch" type="search" placeholder="搜索标题或提案编号"></label><label>开始日期<input v-model="queueFrom" type="date"></label><label>结束日期<input v-model="queueTo" type="date"></label></div>
        <p v-if="!meeting.mayReview" class="queue-permission" role="note">当前账号可查看提案记录；审核与执行仅管理员或负责人可以操作。你可以打开来源分析查看状态和处理历史。</p>
        <p v-if="queueLoading" role="status">正在读取各来源分析、审核和执行记录…</p>
        <p v-if="queueErrors.length" role="alert">有 {{ queueErrors.length }} 个来源列表暂时不可用，当前结果可能不完整。{{ queueErrors.slice(0,3).map(e => `${e.meetingTitle} · ${e.source}`).join('；') }} <button @click="refreshQueue">重试</button></p>
        <article v-for="row in visibleQueue" :key="row.id" class="sug-card queue-card" :data-queue-row="row.id" :data-meeting-suggestion="row.source === 'general' ? row.proposalId : null">
          <div class="sug-head"><b>{{ row.sourceLabel }}</b><span class="sug-status" :class="row.status">{{ row.status === 'pending' ? '待审核' : row.status === 'approved' ? '已批准' : row.status === 'rejected' ? '已拒绝' : row.status }}</span><span class="small">{{ row.createdAt }}</span></div>
          <div class="sug-row"><b>会议</b><span>{{ row.meetingTitle }}</span></div>
          <div class="sug-row"><b>提案 / 影响</b><span>{{ row.proposalId }} · {{ row.summaryText }}</span></div>
          <div class="sug-row"><b>流程进度</b><span>{{ row.flowStage || '分析完成 · 处理状态待确认' }}</span></div>
          <div class="sug-row"><b>审核留痕</b><span>{{ row.proposal?.decision ? `审核决定：${row.proposal.decision}` : '尚无审核决定' }}{{ row.reviewer ? ` · 审核人 #${row.reviewer}` : '' }}{{ row.reviewedAt ? ` · ${row.reviewedAt}` : '' }}{{ row.reviewReason ? ` · ${row.reviewReason}` : '' }}</span></div>
          <div class="sug-row"><b>执行状态</b><span>{{ executionLabel(row) }}{{ row.executor ? ` · 执行人 #${row.executor}` : '' }}{{ row.executedAt ? ` · ${row.executedAt}` : '' }}</span></div>
          <details><summary>查看原始提案、批准内容与证据</summary><pre class="queue-detail">{{ JSON.stringify(row.proposal?.original_proposal || row.proposal, null, 2) }}</pre><blockquote v-for="(evidence,index) in (row.proposal?.original_proposal?.evidence || [])" :key="index">{{ evidence.segment_id || '原文证据' }} · {{ evidence.quote || evidence.text }}</blockquote><p v-if="row.proposal?.approved_proposal" class="small">批准后的变更：{{ JSON.stringify(row.proposal.approved_proposal) }}</p></details>
          <div v-if="row.source === 'general' && row.status === 'pending' && meeting.mayReview" class="sug-acts">
            <button type="button" :data-edit-suggestion="row.proposalId" :disabled="meeting.busy.includes(row.proposalId)" @click="serverEdit(row.proposalId)">修改后采纳</button>
            <button type="button" class="approve" :data-meeting-review="row.proposalId" data-decision="approve" :disabled="meeting.busy.includes(row.proposalId)" @click="serverDecide(row.proposalId, 'approve')">采纳</button>
            <button type="button" class="reject" :data-meeting-review="row.proposalId" data-decision="reject" :disabled="meeting.busy.includes(row.proposalId)" @click="serverDecide(row.proposalId, 'reject')">拒绝</button>
          </div>
          <button type="button" :data-open-meeting="row.source === 'general' ? row.meetingId : null" @click="openSource(row)">{{ row.source === 'general' ? '查看来源会议' : row.status === 'pending' && meeting.mayReview ? '打开来源并审核' : row.status === 'approved' && row.executionStatus === 'not_started' && meeting.mayReview ? '打开来源并执行已批准变更' : '查看来源分析与处理记录' }}</button>
        </article>
        <button v-if="pagedQueue && nextCursor" type="button" :disabled="queueLoading" @click="loadMore">{{ queueLoading ? '加载中…' : '加载更多提案' }}</button>
        <p v-if="!queueLoading && !visibleQueue.length" class="empty">{{ queueErrors.length ? '当前未加载到提案；部分来源读取失败，请刷新。' : '当前筛选下没有提案记录。' }}</p>
      </section>
      <p class="pool-note">通用会议建议只支持创建需求池条目；审核、拒绝和修改后采纳均使用现有服务器接口。</p>
    </div>

    <details class="decision">
      <summary>{{ online ? '📋 决策留痕（服务器）' : '📋 决策留痕（本机演示）' }}</summary>
      <div class="logbody" id="decision-log">
        <template v-if="online">
          <div v-for="s in serverDecisions" :key="s.id" class="logrow">
            <time>{{ s.reviewed_at }} UTC</time><b>{{ s.id }}</b>
            <span>审核人 #{{ s.reviewed_by }} · {{ serverStatusName[s.status] }} · {{ s.reason || '未填写理由' }}{{ s.pool_item_id ? ' · ' + s.pool_item_id : '' }}</span>
          </div>
          <div v-if="!serverDecisions.length" class="empty">暂无服务器审核记录</div>
        </template>
        <template v-else>
          <div v-for="(d, i) in decisions" :key="i" class="logrow"><time>{{ d.t }}</time><b>{{ d.id }}</b><span>{{ d.act }}{{ d.reason ? ' · ' + d.reason : '' }}</span></div>
          <div v-if="!decisions.length" class="empty">暂无决策记录（采纳 / 修改 / 拒绝后自动记录）</div>
        </template>
      </div>
    </details>
    <ReviewEditDialog ref="editRef" />
  </section>
</template>

<style scoped>
.review-queue{min-width:0;margin:18px 0 28px;padding:16px;border:1px solid var(--line,#d8dce5);border-radius:10px}.review-queue h2{margin:0 0 6px}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.queue-filters{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,190px),1fr));gap:12px;margin:12px 0}.queue-filters label{display:grid;align-content:start;gap:5px;min-width:0;font-size:13px;font-weight:700}.queue-filters input,.queue-filters select{box-sizing:border-box;width:100%;min-width:0}.queue-card{min-width:0;margin:12px 0}.queue-card .sug-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.queue-card .sug-row{grid-template-columns:minmax(84px,100px) minmax(0,1fr)}.queue-card .sug-row span,.queue-card details{min-width:0;overflow-wrap:anywhere}.queue-card details summary{cursor:pointer;min-height:36px;display:flex;align-items:center}.queue-detail{max-height:260px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere}.review-queue button{min-height:38px}
@media(max-width:600px){.review-queue{padding:10px}.queue-filters{grid-template-columns:minmax(0,1fr)}.queue-card{padding:12px}.queue-card .sug-row{grid-template-columns:minmax(0,1fr);gap:3px}.panel-heading>button{width:100%}}
</style>
