<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { refinementAnalysesApi } from '@/api/refinementAnalyses'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectStore } from '@/stores/project'
import RefinementProposalReviewForm from './RefinementProposalReviewForm.vue'
import RefinementProposalExecute from './RefinementProposalExecute.vue'
import RefinementAnalysisForm from './RefinementAnalysisForm.vue'
import RefinementStoryFields from './RefinementStoryFields.vue'

const props = defineProps({ meetingId: { type: String, required: true } })
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
const reviewName = value => ({ pending: '待审核', approved: '已批准', rejected: '已拒绝' })[value] ?? '未知审核状态'
const progressName = value => ({ pending: '待审核', partially_reviewed: '部分已审核', reviewed: '全部已审核', no_changes: '无新故事提案' })[value] ?? '未知状态'
const executionName = value => ({ not_started: '未执行', not_applicable: '不适用', succeeded: '执行成功' })[value] ?? '未知执行状态'
const decisionName = value => ({ approve: '接受', modify_and_approve: '修改后接受', reject: '拒绝' })[value] ?? value
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
      refinementAnalysesApi.reviews(props.meetingId, selected.value),
      refinementAnalysesApi.executions(props.meetingId, selected.value)
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
  const previous = typeof preferredId === 'string' ? preferredId : selected.value
  selected.value = ''
  ++detailVersion
  reviews.value = null
  executions.value = []
  analyses.value = []
  error.value = ''
  loading.value = !!props.meetingId
  if (!props.meetingId) return
  try {
    const rows = await refinementAnalysesApi.list(props.meetingId)
    if (version !== listVersion) return
    analyses.value = rows
    selected.value = rows.some(item => item.id === previous) ? previous : rows[0]?.id || ''
  } catch {
    if (version === listVersion) error.value = 'Refinement分析记录加载失败，请重试。'
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
  <section class="status-analysis" aria-label="Refinement新故事提案" :aria-busy="loading || detailLoading">
    <RefinementAnalysisForm :meeting-id="meetingId" @saved="refresh" />
    <div class="panel-heading">
      <h3>Refinement新故事提案</h3>
      <button type="button" @click="refresh" :disabled="!meetingId || loading">刷新Refinement提案</button>
    </div>
    <p class="small">查看已保存的Backlog Refinement分析。批准表示审核通过，执行成功后才会创建故事。</p>
    <p v-if="!meetingId">请先选择已保存会议。</p>
    <p v-else-if="loading" role="status">正在加载Refinement分析…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!analyses.length">此会议暂无已保存的Refinement分析。</p>
    <template v-else>
      <label class="field">分析记录
        <select v-model="selected" aria-label="Refinement分析记录">
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
          <article v-for="item in reviews.proposals" :key="item.proposal_id" class="proposal-card">
            <h4>{{ item.original_proposal.changes.title }}</h4>
            <p class="small">提案 {{ item.proposal_id }} · {{ reviewName(item.status) }} · {{ executionName(execution(item.proposal_id) ? 'succeeded' : item.execution_status) }}</p>
            <h5>原始提案</h5><RefinementStoryFields :changes="item.original_proposal.changes" />
            <template v-if="item.approved_proposal"><h5>批准后的故事</h5><RefinementStoryFields :changes="item.approved_proposal.changes" /></template>
            <p class="preserve">提案原因：{{ item.original_proposal.reason }}</p>
            <div class="evidence">
              <b>原文证据</b>
              <blockquote v-for="(evidence, index) in item.original_proposal.evidence" :key="index"><span class="small">{{ evidence.segment_id }}</span> {{ evidence.quote }}</blockquote>
            </div>
            <p v-if="item.decision" class="preserve">{{ decisionName(item.decision) }} · 审核人 #{{ item.reviewed_by }} · {{ item.reviewed_at }}<br>审核意见：{{ item.reason || '未填写' }}</p>
            <p v-if="execution(item.proposal_id)" class="preserve">已创建故事：{{ execution(item.proposal_id).story_id }}（初始待办、未分配负责人）<br>执行人 #{{ execution(item.proposal_id).executed_by }} · {{ execution(item.proposal_id).executed_at }} · 故事日志 #{{ execution(item.proposal_id).story_log_id }}</p>
            <RefinementProposalReviewForm
              v-if="meeting.mayReview && item.status === 'pending'"
              :key="selected + ':' + item.proposal_id"
              :meeting-id="meetingId" :analysis-id="selected" :proposal="item.original_proposal"
              @saved="reviewSaved" @refresh="loadDetails"
            />
            <RefinementProposalExecute
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
