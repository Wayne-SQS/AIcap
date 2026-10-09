<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard, READONLY_TITLE } from '@/composables/usePermissionGuard'
import { useReviewStore } from '@/stores/review'
import { SUG_KEY } from '@/constants'
import AgentRunPanel from './AgentRunPanel.vue'
import RecorderPanel from './RecorderPanel.vue'
import StatusAnalysisPanel from './StatusAnalysisPanel.vue'
import PlanningAnalysisPanel from './PlanningAnalysisPanel.vue'
import ReviewAnalysisPanel from './ReviewAnalysisPanel.vue'
import RetroAnalysisPanel from './RetroAnalysisPanel.vue'
import RefinementAnalysisPanel from './RefinementAnalysisPanel.vue'
import AssignmentPanel from './AssignmentPanel.vue'
const workflowNames = ['text', 'audio', 'manual']
const route = useRoute()
const router = useRouter()
const workflow = ref(workflowNames.includes(String(route.query.workflow)) ? String(route.query.workflow) : 'text')
const analysisType = ref(String(route.query.type || 'daily'))

/* 会议智能体面板:离线=演示转写+生成建议;在线=保存会议/选择/手动录入待审建议
   对齐旧版 renderMeeting + meeting-review.js */
const meeting = useMeetingStore()
watch(() => route.query.type, value => { analysisType.value = String(value || 'daily') })
watch(() => route.query.workflow, value => { workflow.value = workflowNames.includes(String(value)) ? String(value) : 'text' })
function selectWorkflow(value) {
  if (!workflowNames.includes(value)) return
  workflow.value = value
  const query = { ...route.query }
  if (value === 'text') delete query.workflow
  else query.workflow = value
  if (String(route.query.workflow || '') !== (value === 'text' ? '' : value)) router.replace({ query })
}
function handleWorkflowKeydown(event) {
  const current = workflowNames.indexOf(workflow.value)
  let next = current
  if (event.key === 'ArrowRight') next = (current + 1) % workflowNames.length
  else if (event.key === 'ArrowLeft') next = (current + workflowNames.length - 1) % workflowNames.length
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = workflowNames.length - 1
  else return
  event.preventDefault()
  const value = workflowNames[next]
  selectWorkflow(value)
  nextTick(() => document.getElementById(`meeting-workflow-tab-${value}`)?.focus())
}
const review = useReviewStore()
const { notify } = useToast()
const { session, guard } = usePermissionGuard()

const saveForm = reactive({ title: '', transcript: '' })
const saving = ref(false)
const deleting = ref(false)
const proposal = reactive({ title: '', description: '', evidence: '', priority: 'Could', note: '' })
const proposing = ref(false)

/* 删除会议:仅 admin/owner(mayReview 口径);viewer/member 按钮禁用并说明原因,guard 兜底 */
const mayDeleteMeeting = computed(() => meeting.mayReview)
const deleteTitle = computed(() => {
  if (!meeting.selectedMeeting) return '请先选择一个已保存会议'
  if (mayDeleteMeeting.value) return '删除当前会议'
  return session.isViewer ? READONLY_TITLE : '仅管理员或负责人可删除会议'
})
const readonlyTitle = computed(() => (session.isViewer ? READONLY_TITLE : ''))

async function deleteMeeting() {
  const target = meeting.selectedMeeting
  if (!target) { notify('请先选择一个已保存会议'); return }
  if (guard('删除会议')) return
  if (!mayDeleteMeeting.value) { notify('仅管理员或负责人可以删除会议'); return }
  const ok = confirm(
    '确认删除会议「' + target.title + '」？此操作不可恢复：\n' +
    '已有 Daily、Planning、Review、Retro、Refinement 分析记录、已保存分配建议或关联转写版本的会议不能删除，需保留原文及审核执行审计。\n' +
    '没有上述记录时，会删除录音与旧版历史建议；已产生的需求池条目不受影响。'
  )
  if (!ok) return
  deleting.value = true
  try {
    const result = await meeting.removeMeeting(target.id)
    notify(`会议已删除 · 录音 ${result?.audio_deleted ?? 0} 条 · 历史建议 ${result?.suggestions_deleted ?? 0} 条`)
  } catch (err) {
    notify('删除失败：' + err.message)
  } finally { deleting.value = false }
}

const transcriptDemo = [
  ['00:12', '李锐铭', '好，Sprint 2 的重点大家都清楚吗？'],
  ['00:31', '高思晗', '建议把 AI 拆解往后放，先做会议闭环。'],
  ['00:52', '孙秋实', '成员任务图先做负载热力图，活动图后置。'],
  ['01:20', '罗子涵', '录音前要有知情同意，这个验收要写清楚。']
]

async function refresh() {
  try { await meeting.loadAll() } catch (err) { notify('刷新失败：' + err.message) }
}

async function saveMeeting() {
  saving.value = true
  let saved = false
  try {
    await meeting.saveMeeting({ title: saveForm.title, transcript: saveForm.transcript })
    saved = true
    saveForm.title = ''
    saveForm.transcript = ''
    notify('会议已保存到服务器')
  } catch (err) { notify('保存失败：' + err.message) }
  finally { if (!saved) saving.value = false; else saving.value = false }
}

async function submitProposal() {
  proposing.value = true
  let submitted = false
  try {
    await meeting.submitProposal(proposal)
    submitted = true
    proposal.title = ''
    proposal.description = ''
    proposal.evidence = ''
    proposal.note = ''
    notify('建议已保存，等待负责人审核；尚未创建需求')
  } catch (err) { notify('提交失败：' + err.message) }
  finally { proposing.value = false }
}

/* 离线演示:从会议生成建议送审(对齐旧版 #meeting-gen,含只读角色拦截) */
function genDemoSuggestion() {
  if (guard('生成会议建议')) return
  review.suggestions.unshift({
    id: 'SG0' + (review.suggestions.length + 1), agent: '会议智能体', kind: 'meeting',
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    evidence: '会议决议 #3 / 转写 00:52', affected: '成员任务图 · 需求池',
    change: [{ t: '新增需求：成员开发活动图（US28）进入需求池', d: false }],
    note: '会议智能体根据转写生成，未经审核不改动正式数据。', status: 'pending'
  })
  try { localStorage.setItem(SUG_KEY, JSON.stringify(review.suggestions)) } catch { /* ignore */ }
  notify('已生成建议，前往「提案审核中心」处理')
}
</script>

<template>
  <!-- 离线:演示模式 -->
  <div v-if="!meeting.online">
    <div class="rec">
      <div class="eyebrow" style="margin-bottom:8px">离线演示 · 静态会议样例 · 非真实模型分析</div>
      <div style="display:flex;align-items:center;gap:10px"><span class="sug-status">演示数据</span><b>Sprint 2 规划会示例</b><span class="small">不代表当前录音、真实转写或服务器处理状态</span></div>
    </div>
    <div class="transcript" id="meeting-transcript">
      <div v-for="l in transcriptDemo" :key="l[0]" class="tl"><b>{{ l[0] }}</b><span class="spk">{{ l[1] }}</span><span>{{ l[2] }}</span></div>
    </div>
    <div class="sum-grid">
      <div class="sum-card"><h4>📌 会议摘要</h4><ul><li>确认 Sprint 2 聚焦「会议闭环 + 审核中心」</li><li>AI PRD 拆解降级为 Should</li><li>成员任务图先做负载热力图</li></ul></div>
      <div class="sum-card"><h4>✅ 决议</h4><ul><li>#3 会议闭环优先于六项能力</li><li>#4 需求池承接未确认需求</li></ul></div>
      <div class="sum-card"><h4>🚩 行动项</h4><ul><li>高思晗 整理会议转写（W3）</li><li>孙秋实 验证录音接口（W3）</li><li>罗子涵 复核隐私同意机制</li></ul></div>
      <div class="sum-card"><h4>❓ 遗留问题</h4><ul><li>是否要求说话人分离？待确认</li><li>录音用浏览器还是平台接入？</li></ul></div>
    </div>
    <button class="primary" id="meeting-gen" @click="genDemoSuggestion">＋ 生成本机演示建议 → 查看审核演示</button>
    <p class="small">演示建议保存在本机浏览器，不会进入服务器审核或创建真实需求。</p>
  </div>

  <!-- 在线:按用户任务分流，原有 API 与审核逻辑保持不变 -->
  <div v-else>
    <div class="sum-card" style="margin-bottom:16px"><b>当前模式：在线服务</b><p class="small">{{ meeting.selectedMeeting ? `当前会议：${meeting.selectedMeeting.title}` : '请选择已保存会议或创建会议。' }} · 分析完成后仍需审核，批准不会自动执行业务变更。</p><span class="sug-status">{{ meeting.selectedMeeting ? '已选择会议' : '等待选择会议' }}</span></div>
    <div role="tablist" aria-label="会议工作流" class="meeting-workflow-tabs">
      <button id="meeting-workflow-tab-text" role="tab" aria-controls="meeting-workflow-panel" :tabindex="workflow === 'text' ? 0 : -1" :aria-selected="workflow === 'text'" @click="selectWorkflow('text')" @keydown="handleWorkflowKeydown">文本会议</button>
      <button id="meeting-workflow-tab-audio" role="tab" aria-controls="meeting-workflow-panel" :tabindex="workflow === 'audio' ? 0 : -1" :aria-selected="workflow === 'audio'" @click="selectWorkflow('audio')" @keydown="handleWorkflowKeydown">语音会议</button>
      <button id="meeting-workflow-tab-manual" role="tab" aria-controls="meeting-workflow-panel" :tabindex="workflow === 'manual' ? 0 : -1" :aria-selected="workflow === 'manual'" @click="selectWorkflow('manual')" @keydown="handleWorkflowKeydown">手动录入一条待审核建议</button>
    </div>
    <div id="meeting-workflow-panel" role="tabpanel" :aria-labelledby="`meeting-workflow-tab-${workflow}`" tabindex="0">
    <div v-if="workflow !== 'manual'" class="meeting-shared">
      <p class="pool-note">{{ workflow === 'text' ? '创建或选择会议，录入文本后选择分析类型。' : '录音和转写草稿需要人工核对并保存版本；确认文本后创建分析会议，再选择分析类型。匿名说话人标签不代表成员身份。' }}</p>
      <form v-if="workflow === 'text'" id="meeting-save-form" @submit.prevent="saveMeeting">
      <label class="field">会议标题<input name="title" v-model="saveForm.title" required maxlength="200"></label>
      <label class="field">会议转写<textarea name="transcript" v-model="saveForm.transcript" required maxlength="16000" rows="5"></textarea></label>
      <button class="primary" type="submit" :disabled="!meeting.maySubmit || saving" :title="readonlyTitle">保存会议</button>
      </form>
      <div class="h-sec">已保存会议</div>
      <select id="meeting-select" aria-label="已保存会议" :value="meeting.selected" @change="meeting.select($event.target.value)">
      <option value="">请选择会议</option>
      <option v-for="m in meeting.meetings" :key="m.id" :value="m.id">{{ m.title }}</option>
      </select>
      <button type="button" id="meeting-refresh" @click="refresh">刷新会议与建议</button>
    <button
      type="button" id="meeting-delete" class="danger"
      :disabled="!meeting.selectedMeeting || !mayDeleteMeeting || deleting"
      :title="deleteTitle"
      @click="deleteMeeting"
    >{{ deleting ? '删除中…' : '删除当前会议' }}</button>
      <pre id="saved-transcript" style="white-space:pre-wrap;max-height:240px;overflow:auto">{{ meeting.selectedMeeting?.transcript || '暂无会议，请先保存。' }}</pre>
      <div v-if="workflow === 'audio'"><div class="h-sec">语音输入与转写</div><RecorderPanel /><div class="small">转写是草稿。核对并保存转写版本后，创建分析会议；创建会议本身不会自动运行分析或执行提案。</div></div>
      <template v-if="meeting.selected">
        <div class="h-sec">选择会议分析类型</div>
        <label v-if="analysisType !== 'assignment'" class="field">分析类型<select v-model="analysisType" aria-label="会议分析类型"><option value="daily">Daily · 更新已有故事状态</option><option value="planning">Sprint Planning · 调整 Sprint</option><option value="review">Sprint Review · 标记验收完成</option><option value="retro">Sprint Retro · 创建独立行动项</option><option value="refinement">Backlog Refinement · 补齐并创建新故事</option></select></label>
        <div class="sum-card"><b>当前会议 · {{ meeting.selectedMeeting?.title }}</b><p class="small">{{ analysisType === 'assignment' ? '分配候选先进行只读查询；保存建议后仍需审核，并单独执行。' : analysisType === 'daily' ? '结果可能提出已有故事状态更新。' : analysisType === 'planning' ? '结果可能提出 Sprint 调整。' : analysisType === 'review' ? '结果可能提出验收完成状态。' : analysisType === 'retro' ? '结果可能提出独立行动项。' : '结果可能提出补齐信息并创建新故事。' }} 未知信息会保持待确认。</p></div>
        <StatusAnalysisPanel v-if="analysisType === 'daily'" :key="meeting.selected + ':' + route.query.analysisId" :meeting-id="meeting.selected" :initial-analysis-id="route.query.analysisId" />
        <PlanningAnalysisPanel v-else-if="analysisType === 'planning'" :key="'planning:' + meeting.selected + ':' + route.query.analysisId" :meeting-id="meeting.selected" :initial-analysis-id="route.query.analysisId" />
        <ReviewAnalysisPanel v-else-if="analysisType === 'review'" :key="'review:' + meeting.selected + ':' + route.query.analysisId" :meeting-id="meeting.selected" :initial-analysis-id="route.query.analysisId" />
        <RetroAnalysisPanel v-else-if="analysisType === 'retro'" :key="'retro:' + meeting.selected + ':' + route.query.analysisId" :meeting-id="meeting.selected" :initial-analysis-id="route.query.analysisId" />
        <RefinementAnalysisPanel v-else-if="analysisType === 'refinement'" :key="'refinement:' + meeting.selected + ':' + route.query.analysisId" :meeting-id="meeting.selected" :initial-analysis-id="route.query.analysisId" />
        <details><summary>通用会议 Agent（分析摘要与行动项）</summary><AgentRunPanel /></details>
        <details :open="analysisType === 'assignment'"><summary>Assignment 分配建议 · 只读查询后可另行保存建议</summary><AssignmentPanel :key="'assignment:' + meeting.selected" :meeting-id="meeting.selected" /></details>
      </template>
      <p v-else class="empty">先创建或选择会议，再开始分析。</p>
    </div>
    <form v-else id="meeting-proposal-form" @submit.prevent="submitProposal" class="manual-proposal-flow">
      <div class="sum-card"><h3>手动录入一条待审核建议</h3><p>由用户填写，未由模型生成。提交会创建一条 origin=manual 的待审建议；管理员或负责人审核后才会创建需求池条目。</p></div>
      <label class="field">关联会议<select :value="meeting.selected" @change="meeting.select($event.target.value)" required><option value="">请选择会议</option><option v-for="m in meeting.meetings" :key="m.id" :value="m.id">{{ m.title }}</option></select></label>
      <pre class="transcript-evidence">{{ meeting.selectedMeeting?.transcript || '请先选择一条会议以核对原文证据。' }}</pre>
      <label class="field">需求标题<input name="title" v-model="proposal.title" required maxlength="200"></label>
      <label class="field">需求描述<textarea name="description" v-model="proposal.description" maxlength="10000" rows="3"></textarea></label>
      <label class="field">会议证据（复制原文中的连续片段）<textarea name="evidence" v-model="proposal.evidence" required maxlength="5000" rows="2"></textarea></label>
      <label class="field">优先级（录入人选择，默认 Could）<select name="priority" v-model="proposal.priority"><option>Could</option><option>Should</option><option>Must</option></select></label>
      <label class="field">建议说明<input name="note" v-model="proposal.note" maxlength="2000"></label>
      <button class="primary" type="submit" :disabled="!meeting.selectedMeeting || !meeting.maySubmit || proposing" :title="readonlyTitle">提交待审建议</button>
      <p class="small">输入方式：用户手动录入 · 审核通过后再创建需求 · 拒绝不改变项目数据。</p>
    </form>
    </div>
  </div>
</template>

<style scoped>
.meeting-workflow-tabs{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}.meeting-workflow-tabs button[aria-selected="true"]{outline:2px solid currentColor;font-weight:700}.meeting-shared,.manual-proposal-flow{padding:14px;border:1px solid var(--line,#d8dce5);border-radius:8px}.transcript-evidence{white-space:pre-wrap;max-height:220px;overflow:auto;padding:12px;background:var(--surface-2,#f5f6f8)}
</style>
