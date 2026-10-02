<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { retroAnalysesApi as api } from '@/api/retroAnalyses'
import { membersApi } from '@/api/members'
import { useMeetingStore } from '@/stores/meeting'
import RetroAnalysisForm from './RetroAnalysisForm.vue'
import RetroProposalReviewForm from './RetroProposalReviewForm.vue'
import RetroProposalExecute from './RetroProposalExecute.vue'
const props = defineProps({ meetingId: String })
const meeting = useMeetingStore()
const analyses = ref([]), selected = ref(''), reviews = ref(null), executions = ref([]), confirmed = ref([])
const loading = ref(false), error = ref(''), detailError = ref(''), detailLoading = ref(false), notice = ref('')
const members = ref([]), membersReady = ref(false), memberError = ref('')
const actions = ref([]), actionError = ref(''), logs = ref({}), logErrors = ref({})
let listVersion = 0, detailVersion = 0, actionVersion = 0, memberVersion = 0, alive = true
const logVersions = new Map()
const analysis = computed(() => analyses.value.find(item => item.id === selected.value))
const reviewName = state => ({ pending: '待审核', approved: '已批准', rejected: '已拒绝' })[state] || state
const progressName = state => ({ pending: '待审核', partially_reviewed: '部分已审核', reviewed: '全部已审核', no_changes: '无行动提案' })[state] || state
const ownerName = id => id == null ? '未确定' : `${analysis.value?.member_snapshots?.find(m => m.user_id === id)?.display_name || '成员'} #${id}`
const execution = id => confirmed.value.find(item => item.proposal_id === id) || executions.value.find(item => item.proposal_id === id)
async function loadDetails() {
  const version = ++detailVersion
  reviews.value = null; executions.value = []; detailError.value = ''; detailLoading.value = !!selected.value
  if (!selected.value) return
  try {
    const [reviewRows, executionRows] = await Promise.all([api.reviews(props.meetingId, selected.value), api.executions(props.meetingId, selected.value)])
    if (version !== detailVersion) return
    reviews.value = reviewRows; executions.value = executionRows
  } catch { if (version === detailVersion) detailError.value = '审核和执行状态加载失败，请重试。' }
  finally { if (version === detailVersion) detailLoading.value = false }
}
async function refresh(preferredId) {
  const version = ++listVersion, previous = typeof preferredId === 'string' ? preferredId : selected.value
  selected.value = ''; ++detailVersion; reviews.value = null; executions.value = []; analyses.value = []; error.value = ''; loading.value = !!props.meetingId
  if (!props.meetingId) return
  try {
    const rows = await api.list(props.meetingId)
    if (version !== listVersion) return
    analyses.value = rows; selected.value = rows.some(item => item.id === previous) ? previous : rows[0]?.id || ''
  } catch { if (version === listVersion) error.value = 'Retro分析记录加载失败，请重试。' }
  finally { if (version === listVersion) loading.value = false }
}
async function loadMembers() {
  const version = ++memberVersion
  membersReady.value = false; memberError.value = ''
  try {
    const rows = await membersApi.profiles()
    if (version !== memberVersion) return
    members.value = rows; membersReady.value = true
  } catch { if (version === memberVersion) memberError.value = '成员目录加载失败，暂不能修改后接受。' }
}
async function loadActions() {
  const version = ++actionVersion
  actionError.value = ''
  if (!props.meetingId) { actions.value = []; return }
  try { const rows = await api.actions(props.meetingId); if (version === actionVersion) actions.value = rows }
  catch { if (version === actionVersion) actionError.value = '行动项加载失败，请重试。' }
}
async function loadLogs(id) {
  const version = (logVersions.get(id) || 0) + 1, meetingId = props.meetingId
  logVersions.set(id, version); logErrors.value[id] = ''
  try { const rows = await api.logs(meetingId, id); if (alive && props.meetingId === meetingId && logVersions.get(id) === version) logs.value[id] = rows }
  catch { if (alive && props.meetingId === meetingId && logVersions.get(id) === version) logErrors.value[id] = '审计加载失败，请重试。' }
}
async function reviewSaved() { notice.value = '审核决定已保存；接受的提案尚未执行。'; await loadDetails() }
async function executionSaved(result) {
  confirmed.value = [...confirmed.value.filter(item => item.proposal_id !== result.proposal_id), result]
  notice.value = `执行结果已确认，行动项审计 #${result.action_item_log_id}。`
  await Promise.all([loadDetails(), loadActions()])
}
watch(selected, () => { notice.value = ''; confirmed.value = []; loadDetails() })
watch(() => props.meetingId, () => { actions.value = []; logs.value = {}; logErrors.value = {}; refresh(); loadActions(); loadMembers() }, { immediate: true })
onBeforeUnmount(() => { alive = false; ++listVersion; ++detailVersion; ++actionVersion; ++memberVersion })
</script>

<template>
  <section class="retro-panel" aria-label="Retro行动提案" :aria-busy="loading || detailLoading">
    <RetroAnalysisForm :meeting-id="meetingId" @saved="refresh" />
    <h3>Retro行动提案</h3>
    <button type="button" :disabled="!meetingId || loading" @click="refresh">刷新Retro提案</button>
    <p>批准表示审核通过，执行成功后才创建行动项。截止时间按文本保留。</p>
    <p v-if="!meetingId">请先选择已保存会议。</p>
    <p v-else-if="loading" role="status">正在加载Retro分析…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!analyses.length">此会议暂无已保存的Retro分析。</p>
    <label v-if="analyses.length" class="field">分析记录<select v-model="selected" aria-label="Retro分析记录"><option v-for="item in analyses" :key="item.id" :value="item.id">{{ item.created_at }} · {{ item.client_request_id }}</option></select></label>
    <template v-if="analysis">
      <p class="small">提交人 #{{ analysis.submitted_by }} · {{ analysis.created_at }}</p>
      <p class="preserve">{{ analysis.result.summary }}</p>
      <details><summary>分析保存时的会议原文</summary><pre>{{ analysis.transcript }}</pre></details>
      <div v-if="analysis.result.decisions?.length"><h4>会议决议</h4><article v-for="(decision, i) in analysis.result.decisions" :key="i"><p>{{ decision.text }}</p><blockquote v-for="(e, j) in decision.evidence" :key="j">{{ e.segment_id }} · {{ e.quote }}</blockquote></article></div>
      <div v-if="analysis.result.open_questions?.length"><h4>待确认问题</h4><ul><li v-for="(q, i) in analysis.result.open_questions" :key="i">{{ q }}</li></ul></div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <div v-if="memberError && meeting.mayReview" role="alert">{{ memberError }} <button type="button" @click="loadMembers">重试成员目录</button></div>
      <p v-if="detailLoading" role="status">正在加载审核和执行状态…</p>
      <div v-else-if="detailError" role="alert">{{ detailError }} <button type="button" @click="loadDetails">重试加载处理状态</button></div>
      <template v-else-if="reviews">
        <p role="status">审核进度：{{ progressName(reviews.review_status) }}</p>
        <article v-for="item in reviews.proposals" :key="selected + ':' + item.proposal_id" class="proposal-card">
          <h4>{{ item.original_proposal.changes.title }}</h4>
          <p>提案 {{ item.proposal_id }} · {{ reviewName(item.status) }} · {{ execution(item.proposal_id) ? '执行成功' : item.status === 'rejected' ? '不适用' : '未执行' }}</p>
          <p class="preserve">原始事项：{{ item.original_proposal.changes.title }}<br>{{ item.original_proposal.changes.description }}<br>负责人：{{ ownerName(item.original_proposal.changes.owner_id) }} · 截止时间：{{ item.original_proposal.changes.deadline_text ?? '未确定' }}</p>
          <p v-if="item.approved_proposal" class="preserve">批准后的事项：{{ item.approved_proposal.changes.title }}<br>{{ item.approved_proposal.changes.description }}<br>批准负责人：{{ item.approved_proposal.changes.owner_id == null ? '未确定' : `${item.approved_owner_name || '成员'} #${item.approved_proposal.changes.owner_id}` }} · 批准截止时间：{{ item.approved_proposal.changes.deadline_text ?? '未确定' }}</p>
          <p class="preserve">提案原因：{{ item.original_proposal.reason }}</p>
          <b>原文证据</b><blockquote v-for="(e, i) in item.original_proposal.evidence" :key="i">{{ e.segment_id }} · {{ e.quote }}</blockquote>
          <p v-if="item.decision" class="preserve">{{ ({ approve: '接受', modify_and_approve: '修改后接受', reject: '拒绝' })[item.decision] }} · 审核人 #{{ item.reviewed_by }} · {{ item.reviewed_at }}<br>审核意见：{{ item.reason || '未填写' }}</p>
          <p v-if="execution(item.proposal_id)">行动项 {{ execution(item.proposal_id).action_item_id }} · 审计 #{{ execution(item.proposal_id).action_item_log_id }} · 执行人 #{{ execution(item.proposal_id).executed_by }} · {{ execution(item.proposal_id).executed_at }}</p>
          <RetroProposalReviewForm v-if="meeting.mayReview && item.status === 'pending'" :meeting-id="meetingId" :analysis-id="selected" :proposal="item.original_proposal" :members="members" :members-ready="membersReady" @saved="reviewSaved" @refresh="loadDetails" />
          <RetroProposalExecute v-if="meeting.mayReview && item.status === 'approved' && item.approved_proposal && item.execution_status === 'not_started' && !execution(item.proposal_id)" :meeting-id="meetingId" :analysis-id="selected" :proposal-id="item.proposal_id" @executed="executionSaved" @refresh="loadDetails" />
        </article>
      </template>
    </template>
    <section aria-label="已创建行动项">
      <h3>已创建行动项</h3><button type="button" :disabled="!meetingId" @click="loadActions">刷新行动项</button>
      <p v-if="actionError" role="alert">{{ actionError }}</p>
      <p v-else-if="!actions.length">此会议暂无已创建行动项。</p>
      <article v-for="item in actions" :key="item.id" class="proposal-card">
        <h4>{{ item.title }}</h4><p class="preserve">{{ item.description }}</p>
        <p>负责人：{{ item.owner_id == null ? '未确定' : `#${item.owner_id}` }} · 截止时间：{{ item.deadline_text ?? '未确定' }} · {{ item.status === 'open' ? '待跟进' : item.status }}</p>
        <p class="small">行动项 {{ item.id }} · 创建人 #{{ item.created_by }} · {{ item.created_at }}</p>
        <button type="button" @click="loadLogs(item.id)">查看行动项审计</button>
        <p v-if="logErrors[item.id]" role="alert">{{ logErrors[item.id] }}</p>
        <div v-for="log in logs[item.id]" :key="log.id"><p>审计 #{{ log.id }} · {{ log.log_type === 'create' ? '创建' : log.log_type }} · 操作人 #{{ log.user_id }} · {{ log.created_at }}</p><details><summary>创建时的批准提案</summary><pre>{{ log.approved_proposal_json }}</pre></details></div>
      </article>
    </section>
  </section>
</template>
<style scoped>
.retro-panel { margin: 20px 0; padding: 16px; border: 1px solid var(--line, #ddd); border-radius: 10px; }
.proposal-card { border-top: 1px solid var(--line, #ddd); margin-top: 16px; padding-top: 12px; overflow-wrap: anywhere; }
.preserve, blockquote, pre { white-space: pre-wrap; overflow-wrap: anywhere; }
pre { max-height: 260px; overflow: auto; }
blockquote { margin: 8px 0; border-left: 3px solid var(--line, #ddd); padding-left: 12px; }
select { width: 100%; }
</style>
