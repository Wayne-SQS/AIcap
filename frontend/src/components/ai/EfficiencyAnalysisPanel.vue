<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { efficiencyAnalysisApi } from '@/api/efficiencyAnalysis'
import { api } from '@/api/client'
import { useRouter } from 'vue-router'
const projects = useProjectsStore(), session = useSessionStore()
const route = useRoute()
const router = useRouter()
const result = ref(null), loading = ref(false), error = ref(''), submitError = ref('')
const reviewReason = ref({}), reviewNotice = ref('')
const canManage = () => Boolean(projects.currentProject?.can_manage)
const severity = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const severityClass = value => ({ HIGH: 'high', MEDIUM: 'medium', LOW: 'low' }[value] || 'unknown')
async function load() {
  if (!projects.currentProjectId || !session.online) return
  error.value = ''
  try { result.value = route.query.analysisId
    ? await efficiencyAnalysisApi.get(projects.currentProjectId, String(route.query.analysisId))
    : await efficiencyAnalysisApi.latest(projects.currentProjectId) }
  catch (e) { error.value = e.message || '效率分析读取失败' }
}
async function run() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { result.value = await efficiencyAnalysisApi.run(projects.currentProjectId) }
  catch (e) { error.value = e.message || '效率分析失败' }
  finally { loading.value = false }
}
async function submit(recommendation) {
  submitError.value = ''
  try {
    await efficiencyAnalysisApi.submit(projects.currentProjectId, result.value.analysisId, recommendation.id)
    recommendation.status = 'submitted'
  } catch (e) { submitError.value = e.message || '建议提交失败' }
}
async function review(item, decision) {
  const reason = reviewReason.value[item.proposalId]?.trim()
  if (!reason) { reviewNotice.value = '审核请填写理由'; return }
  try {
    const res = await api(`/api/projects/${encodeURIComponent(projects.currentProjectId)}/efficiency-analysis/proposals/${encodeURIComponent(item.proposalId)}/review`,
      { method: 'POST', body: JSON.stringify({ decision, reason }) })
    item.status = res.status
    reviewNotice.value = decision === 'approve' ? '建议已批准，仍未执行任何项目变更。' : '建议已拒绝，未修改项目数据。'
  } catch (e) { reviewNotice.value = e.message || '审核失败' }
}
async function planApproved(item) {
  try {
    const run = await efficiencyAnalysisApi.planApproved(projects.currentProjectId, item.id)
    await router.push({ name: projects.currentProjectId ? 'project-agents' : 'agents',
      params: projects.currentProjectId ? { projectId: projects.currentProjectId } : undefined,
      query: { agent: 'planning', runId: run.id } })
  } catch (e) { reviewNotice.value = e.message || '规划草案创建失败' }
}
onMounted(load)
</script>
<template>
  <div class="efficiency-analysis-panel">
    <div class="analysis-actions"><button class="primary" type="button" :disabled="loading || !session.online" @click="run">{{ loading ? '正在分析…' : '分析当前项目' }}</button><button class="secondary-action" type="button" :disabled="loading || !session.online" @click="load">读取最近结果</button></div>
    <p v-if="!session.online" class="panel-notice" role="status">连接在线服务后可分析当前项目真实数据。</p><p v-if="error" class="panel-notice error" role="alert">{{ error }}</p>
    <template v-if="result?.analysisId">
      <header class="report-head">
        <div class="report-title"><span class="eyebrow">EFFICIENCY REPORT / US23</span><h3>项目效率分析</h3><p>{{ result.summary }}</p></div>
        <div class="report-meta">
          <span class="report-tag"><small>分析时间</small><strong>{{ result.generatedAt || result.createdAt }}</strong></span>
          <span class="report-tag"><small>待复核瓶颈</small><strong>{{ result.bottlenecks?.length || 0 }} 项</strong></span>
          <span class="report-tag"><small>分析依据</small><strong>{{ result.engine === 'rules_and_ai' ? '规则证据 + AI解释' : '规则证据降级结果' }}</strong></span>
        </div>
      </header>
      <p v-if="!result.bottlenecks?.length" class="empty">暂无能被当前证据确认的效率瓶颈。</p>
      <div v-else class="bottleneck-list">
        <article v-for="item in result.bottlenecks" :key="item.id" class="efficiency-bottleneck" :class="`severity-${severityClass(item.severity)}`">
          <header class="bottleneck-heading">
            <div class="bottleneck-title"><span class="object-code">{{ item.affectedTaskCodes?.join('、') || item.affectedStoryCodes?.join('、') || item.id }}</span><h4>{{ item.title }}</h4></div>
            <div class="bottleneck-tags"><span class="type-tag">{{ item.type }}</span><span class="severity-tag">{{ severity[item.severity] || item.severity }}</span></div>
          </header>
          <p class="bottleneck-description">{{ item.description }}</p>
          <details class="evidence-details">
            <summary>查看证据与建议</summary>
            <div class="evidence-advice-grid">
              <section class="detail-section evidence-section"><h5>证据</h5>
                <p v-if="item.evidence_sufficient === false" class="evidence-caution">证据不足</p>
                <ul><li v-for="e in item.evidence" :key="e">{{ e }}</li></ul>
                <p v-if="item.aiExplanation?.possibleCause" class="possible-cause">可能原因：{{ item.aiExplanation.possibleCause }}</p>
              </section>
              <section class="detail-section recommendation-section"><h5>优化建议</h5>
                <ul class="recommendation-list"><li v-for="rec in item.recommendations" :key="rec.id"><span>{{ rec.description }}</span><button type="button" :disabled="rec.status === 'submitted' || session.isViewer" @click="submit(rec)">{{ rec.status === 'submitted' ? '已提交审核' : '提交审核' }}</button></li></ul>
              </section>
            </div>
          </details>
        </article>
      </div>
      <p v-if="submitError" class="panel-notice error" role="alert">{{ submitError }}</p>
      <section v-if="result.limitations?.length" class="analysis-limitations"><h4>分析说明 / 数据限制</h4><ul><li v-for="note in result.limitations" :key="note">{{ note }}</li></ul></section>
      <section v-if="result.proposals?.length" class="efficiency-proposals">
        <h3>当前分析的待审核建议</h3>
        <article v-for="item in result.proposals" :key="item.id">
          <strong>{{ item.proposal?.title || item.proposal?.description }}</strong><span>{{ item.status }}</span>
          <p>{{ item.proposal?.recommendations?.[0]?.description }}</p>
          <label>审核理由 <input v-model="reviewReason[item.id]" maxlength="1000"></label>
          <button type="button" :disabled="item.status !== 'pending' || !canManage()" @click="review(item, 'approve')">批准（不执行）</button>
          <button type="button" :disabled="item.status !== 'pending' || !canManage()" @click="review(item, 'reject')">拒绝</button>
          <button type="button" :disabled="item.status !== 'approved' || !canManage()" @click="planApproved(item)">生成正式规划草案</button>
        </article>
        <p v-if="reviewNotice" role="status">{{ reviewNotice }}</p>
      </section>
    </template><p v-else-if="!loading && !error" class="empty">尚未生成分析结果。分析不会直接修改任务或成员数据。</p>
  </div>
</template>

<style scoped>
.efficiency-analysis-panel{min-width:0;max-width:1180px;margin:0 auto;padding:2px 4px 16px;color:var(--ink)}
.analysis-actions{display:flex;align-items:center;gap:10px;margin:0 0 22px}.analysis-actions button{min-height:40px;padding:8px 14px}.analysis-actions .secondary-action{background:transparent;border-color:var(--line);color:var(--ink-soft);box-shadow:none}.analysis-actions .secondary-action:hover:not(:disabled){background:var(--paper-2);border-color:var(--ink)}
.panel-notice{margin:12px 0;padding:10px 12px;border:1px solid var(--line);background:var(--paper-2);font-size:13px}.panel-notice.error{border-color:var(--orange);color:#773e20}
.report-head{display:grid;grid-template-columns:minmax(220px,1fr) auto;gap:18px;align-items:center;margin-bottom:18px;padding:16px 18px;border:2px solid var(--ink);border-left:7px solid var(--green-2);background:var(--paper-2)}
.report-title .eyebrow{font:10px/1.4 var(--mono);color:var(--ink-soft)}.report-title h3{margin:4px 0 5px;font-size:19px}.report-title p{margin:0;font-size:13px;line-height:1.6;color:var(--ink-soft)}
.report-meta{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.report-tag{display:flex;flex-direction:column;gap:4px;min-width:112px;max-width:220px;padding:8px 10px;border:1px solid var(--line);background:var(--paper)}.report-tag small{font-size:10px;color:var(--muted)}.report-tag strong{font-size:12px;line-height:1.4;overflow-wrap:anywhere}
.bottleneck-list{display:grid;gap:12px}.efficiency-bottleneck{min-width:0;padding:14px 16px;border:1.5px solid var(--line);border-left:5px solid #aeb9a8;background:var(--paper)}.efficiency-bottleneck.severity-high{border-left-color:#b64b31}.efficiency-bottleneck.severity-medium{border-left-color:#c77c36}.efficiency-bottleneck.severity-low{border-left-color:#8a9a80}
.bottleneck-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.bottleneck-title{min-width:0}.object-code{display:block;margin-bottom:4px;font:11px/1.4 var(--mono);color:var(--ink-soft)}.bottleneck-title h4{margin:0;font-size:16px;line-height:1.45;overflow-wrap:anywhere}.bottleneck-tags{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:6px;flex-shrink:0}.type-tag,.severity-tag{display:inline-flex;align-items:center;min-height:24px;padding:3px 7px;border:1px solid var(--line);font:10px/1.3 var(--mono);white-space:normal}.type-tag{background:var(--paper-2);color:var(--ink-soft)}.severity-tag{font-family:inherit;font-weight:700;background:#edf0e8}.severity-high .severity-tag{background:#f3dfd5;border-color:#c98268;color:#773e20}.severity-medium .severity-tag{background:#f4e8d6;border-color:#d0a36d;color:#704a20}.severity-low .severity-tag{background:#e8eee4;border-color:#aab79f;color:#465741}
.bottleneck-description{margin:9px 0 12px;font-size:13px;line-height:1.65;overflow-wrap:anywhere}.evidence-details{border:1px solid #bbc7b6;background:#f5f7f1}.evidence-details summary{min-height:38px;padding:9px 11px;cursor:pointer;list-style:none;font-size:12px;font-weight:700;color:#234c39}.evidence-details summary::-webkit-details-marker{display:none}.evidence-details summary::before{content:'▸';margin-right:9px;font-size:14px}.evidence-details[open] summary::before{content:'▾'}.evidence-details summary:hover{background:#e9efe5}
.evidence-advice-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px;padding:0 10px 10px}.detail-section{min-width:0;padding:10px 11px;border:1px solid #d4dbce;background:var(--paper)}.detail-section h5{margin:0 0 8px;font-size:12px}.detail-section ul{margin:0;padding-left:18px}.detail-section li{margin:5px 0;font-size:12px;line-height:1.55;overflow-wrap:anywhere}.evidence-caution{display:inline-block;margin:0 0 7px;padding:3px 7px;border:1px solid #d0a36d;background:#f4e8d6;color:#704a20;font-size:11px;font-weight:700}.possible-cause{margin:9px 0 0;padding-top:8px;border-top:1px dashed var(--line);font-size:12px;line-height:1.6}.recommendation-list{list-style:none;padding:0!important}.recommendation-list li{display:flex;justify-content:space-between;align-items:flex-start;gap:10px}.recommendation-list li span{font-size:12px;line-height:1.6}.recommendation-list button{flex:0 0 auto;padding:5px 8px;font-size:11px}
.analysis-limitations{margin-top:18px;padding:11px 14px;border:1px dashed #aab5a2;background:#f5f7f1}.analysis-limitations h4{margin:0 0 7px;font-size:12px}.analysis-limitations ul{margin:0;padding-left:18px}.analysis-limitations li{margin:4px 0;color:var(--ink-soft);font-size:11px;line-height:1.55;overflow-wrap:anywhere}
.empty{padding:16px;border:1px solid var(--line);background:var(--paper-2);font-size:13px}.efficiency-proposals{margin-top:20px;padding-top:14px;border-top:2px solid var(--ink)}.efficiency-proposals>h3{font-size:15px}.efficiency-proposals article{margin:10px 0;padding:12px;border:1px solid var(--line);background:var(--paper-2)}.efficiency-proposals article>strong{margin-right:10px}.efficiency-proposals article p{font-size:12px;line-height:1.55}.efficiency-proposals label{display:flex;align-items:center;gap:8px;margin:8px 0;font-size:12px}.efficiency-proposals input{min-width:0;flex:1}.efficiency-proposals button{margin:4px 5px 0 0}
@media(max-width:760px){.report-head{grid-template-columns:1fr}.report-meta{justify-content:flex-start}.evidence-advice-grid{grid-template-columns:1fr}.bottleneck-heading{flex-direction:column}.bottleneck-tags{justify-content:flex-start}.analysis-actions{flex-wrap:wrap}}
@media(max-width:480px){.efficiency-analysis-panel{padding-inline:0}.report-head{padding:13px}.report-tag{min-width:calc(50% - 5px);flex:1}.efficiency-bottleneck{padding:12px}.recommendation-list li{flex-direction:column}.recommendation-list button{align-self:flex-start}}
</style>
