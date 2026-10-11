<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { statusAnalysesApi } from '@/api/statusAnalyses'
import { executionStatusName } from '@/api/workflowLabels'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectStore } from '@/stores/project'
import WorkflowPermissionNote from './WorkflowPermissionNote.vue'
import StatusProposalReviewForm from './StatusProposalReviewForm.vue'
import StatusProposalExecute from './StatusProposalExecute.vue'
import DailyAnalysisForm from './DailyAnalysisForm.vue'

const props = defineProps({ meetingId: { type: String, required: true }, initialAnalysisId: { type: String, default: '' } })
const meeting = useMeetingStore()
const project = useProjectStore()
const projectError = ref('')
const projectLoading = ref(false)
let mounted = true
const reviewNotice = ref('')
const confirmedExecutions = ref([])
const analyses = ref([])
const selected = ref('')
const loading = ref(false)
const error = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const reviews = ref(null)
const executions = ref([])
let listVersion = 0
let detailVersion = 0
const analysis = computed(() => analyses.value.find(item => item.id === selected.value))
const statusName = value => ({ 0: '待办', 1: '进行中', 2: '已完成' })[value] ?? '未知状态'
const reviewName = value => ({ pending: '待审核', approved: '已批准', rejected: '已拒绝' })[value] ?? '未知审核状态'
const progressName = value => ({ pending: '待审核', partially_reviewed: '部分已审核', reviewed: '全部已审核', no_changes: '无状态变更提案' })[value] ?? '未知状态'
const executionName = executionStatusName
const decisionName = value => ({ approve: '接受', modify_and_approve: '修改后接受', reject: '拒绝' })[value] ?? value
const snapshot = storyId => analysis.value?.story_snapshots?.find(item => item.id === storyId)
const execution = proposalId => confirmedExecutions.value.find(item => item.proposal_id === proposalId) || executions.value.find(item => item.proposal_id === proposalId)

async function loadDetails() {
  const version = ++detailVersion
  reviews.value = null
  executions.value = []
  detailError.value = ''
  detailLoading.value = !!selected.value
  if (!selected.value) return
  try {
    const [reviewRows, executionRows] = await Promise.all([
      statusAnalysesApi.reviews(props.meetingId, selected.value),
      statusAnalysesApi.executions(props.meetingId, selected.value)
    ])
    if (version !== detailVersion) return
    reviews.value = reviewRows
    executions.value = executionRows
  } catch {
    if (version === detailVersion) detailError.value = '审核和执行状态加载失败，请重试。'
  } finally {
    if (version === detailVersion) detailLoading.value = false
  }
}

async function refresh(preferredId = null) {
  const version = ++listVersion
  const previous = typeof preferredId === 'string' ? preferredId : selected.value || props.initialAnalysisId
  selected.value = ''
  ++detailVersion
  reviews.value = null
  executions.value = []
  analyses.value = []
  error.value = ''
  loading.value = !!props.meetingId
  if (!props.meetingId) return
  try {
    const rows = await statusAnalysesApi.list(props.meetingId)
    if (version !== listVersion) return
    analyses.value = rows
    selected.value = rows.some(item => item.id === previous) ? previous : rows[0]?.id || ''
  } catch {
    if (version === listVersion) error.value = '状态分析记录加载失败，请重试。'
  } finally {
    if (version === listVersion) loading.value = false
  }
}

async function reviewSaved() {
  reviewNotice.value = '审核决定已保存；接受的提案尚未执行。'
  await loadDetails()
}
async function refreshProject() {
  const id = selected.value
  projectError.value = ''
  projectLoading.value = true
  try { await project.refreshStoriesAndLogs() }
  catch {
    if (mounted && selected.value === id) projectError.value = '执行结果已确认，但看板和日志刷新失败。'
  } finally {
    if (mounted && selected.value === id) projectLoading.value = false
  }
}
async function executionSaved(result) {
  confirmedExecutions.value = [...confirmedExecutions.value.filter(item => item.proposal_id !== result.proposal_id), result]
  reviewNotice.value = `执行结果已确认，故事日志 #${result.story_log_id}。`
  await Promise.all([loadDetails(), refreshProject()])
}
watch(selected, () => { reviewNotice.value = ''; projectError.value = ''; projectLoading.value = false; confirmedExecutions.value = []; loadDetails() })
watch(() => props.meetingId, () => refresh(), { immediate: true })
onBeforeUnmount(() => { mounted = false; ++listVersion; ++detailVersion })
</script>

<template>
  <section class="status-analysis" aria-label="故事状态提案" :aria-busy="loading || detailLoading">
    <DailyAnalysisForm :meeting-id="meetingId" @saved="refresh" />
    <div class="panel-heading">
      <h3>故事状态提案</h3>
      <button type="button" @click="refresh" :disabled="!meetingId || loading">刷新状态提案</button>
    </div>
    <p class="small">查看已保存的每日站会分析。批准表示审核通过，执行成功后才会更新故事。</p>
    <p v-if="!meetingId">请先选择已保存会议。</p>
    <p v-else-if="loading" role="status">正在加载状态分析…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!analyses.length">此会议暂无已保存的状态分析。</p>
    <template v-else>
      <label class="field">分析记录
        <select v-model="selected" aria-label="状态分析记录">
          <option v-for="item in analyses" :key="item.id" :value="item.id">{{ item.created_at }} · {{ item.client_request_id }}</option>
        </select>
      </label>
      <div v-if="analysis">
        <p class="small">提交人 #{{ analysis.submitted_by }} · {{ analysis.created_at }}</p>
        <p class="preserve">{{ analysis.result.summary }}</p>
        <details>
          <summary>分析保存时的会议原文</summary>
          <pre class="source">{{ analysis.transcript }}</pre>
        </details>
        <div v-if="analysis.result.open_questions?.length">
          <h4>待确认问题</h4>
          <ul><li v-for="(question, index) in analysis.result.open_questions" :key="index">{{ question }}</li></ul>
        </div>
        <div v-if="projectError" role="alert">{{ projectError }} <button type="button" :disabled="projectLoading" @click="refreshProject">刷新看板和日志</button></div>
        <p v-if="reviewNotice" role="status">{{ reviewNotice }}</p>
        <p v-if="detailLoading" role="status">正在加载审核和执行状态…</p>
        <div v-else-if="detailError" role="alert">
          {{ detailError }} <button type="button" @click="loadDetails">重试加载处理状态</button>
        </div>
        <template v-else-if="reviews">
          <p role="status">审核进度：{{ progressName(reviews.review_status) }}</p>
          <WorkflowPermissionNote :proposals="reviews.proposals" />
          <article v-for="item in reviews.proposals" :key="item.proposal_id" class="proposal-card">
            <h4>{{ item.original_proposal.story_id }} · {{ snapshot(item.original_proposal.story_id)?.title || '故事' }}</h4>
            <p class="small">提案 {{ item.proposal_id }} · {{ reviewName(item.status) }} · {{ executionName(execution(item.proposal_id) ? 'succeeded' : item.execution_status) }}</p>
            <p>原始提案：{{ statusName(item.original_proposal.expected.status) }} → {{ statusName(item.original_proposal.changes.status) }}</p>
            <p v-if="item.approved_proposal">批准后的变更：{{ statusName(item.approved_proposal.expected.status) }} → {{ statusName(item.approved_proposal.changes.status) }}</p>
            <p class="preserve">提案原因：{{ item.original_proposal.reason }}</p>
            <div class="evidence">
              <b>原文证据</b>
              <blockquote v-for="(evidence, index) in item.original_proposal.evidence" :key="index"><span class="small">{{ evidence.segment_id }}</span> {{ evidence.quote }}</blockquote>
            </div>
            <p v-if="item.decision" class="preserve">{{ decisionName(item.decision) }} · 审核人 #{{ item.reviewed_by }} · {{ item.reviewed_at }}<br>审核意见：{{ item.reason || '未填写' }}</p>
            <p v-if="execution(item.proposal_id)" class="preserve">执行结果：{{ statusName(execution(item.proposal_id).previous_status) }} → {{ statusName(execution(item.proposal_id).new_status) }}<br>执行人 #{{ execution(item.proposal_id).executed_by }} · {{ execution(item.proposal_id).executed_at }} · 故事日志 #{{ execution(item.proposal_id).story_log_id }}</p>
            <StatusProposalReviewForm
              v-if="meeting.mayReview && item.status === 'pending'"
              :key="selected + ':' + item.proposal_id"
              :meeting-id="meetingId" :analysis-id="selected" :proposal="item.original_proposal"
              @saved="reviewSaved" @refresh="loadDetails"
            />
            <StatusProposalExecute
              v-if="meeting.mayReview && item.status === 'approved' && item.approved_proposal && item.execution_status === 'not_started' && !execution(item.proposal_id)"
              :key="selected + ':execute:' + item.proposal_id"
              :meeting-id="meetingId" :analysis-id="selected" :proposal-id="item.proposal_id"
              @executed="executionSaved" @refresh="loadDetails"
            />
          </article>
        </template>
      </div>
    </template>
  </section>
</template>

<style scoped>
.status-analysis { margin: 20px 0; padding: 16px; border: 1px solid var(--line, #ddd); border-radius: 10px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h3, h4 { margin: 8px 0; }
.proposal-card { border-top: 1px solid var(--line, #ddd); margin-top: 16px; padding-top: 12px; overflow-wrap: anywhere; }
.preserve, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
.source { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow: auto; }
blockquote { margin: 8px 0; border-left: 3px solid var(--line, #ddd); padding-left: 12px; }
select { width: 100%; }
</style>
