<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { getProgressForecast, getProgressForecastRecords, recordProgressForecast, decideProgressForecast } from '@/api/progressForecast'
import { getEfficiencyInsights, createEfficiencyImprovement } from '@/api/efficiencyInsights'
import { getQualityAnalysis, createQualityImprovement } from '@/api/qualityAnalysis'
import { profileAgentApi } from '@/api/profileAgent'
import { memberUserId } from '@/data/memberIdentity'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { MAP_SPRINTS } from '@/data/seed'

/* 对齐旧版 renderOverview(L893-907) + renderRealtime(L1181-1186) + 视图结构 L447-485 */
const router = useRouter()
const project = useProjectStore()
const projects = useProjectsStore()
const session = useSessionStore()
const go = v => router.push({ name: project.currentProjectId ? `project-${v}` : v, params: project.currentProjectId ? { projectId: project.currentProjectId } : {} })

const ovTotal = computed(() => String(project.stories.length).padStart(2, '0'))
const ovTasks = computed(() => project.tasks.length)
const overallPct = computed(() => project.groupPct(project.stories))
const doneCount = computed(() => project.stories.filter(s => s.status === 2).length)
const doingCount = computed(() => project.stories.filter(s => s.status === 1).length)
/** 完成比例:纯计数口径(与后端 /api/dashboard.percent 同口径) */
const realtimePct = computed(() => project.stories.length ? Math.round(doneCount.value / project.stories.length * 100) : 0)
/** 待关注/阻塞:取真实 blocked 标记数(此前是写死的 03,与库里的 0 不符) */
const blockedCount = computed(() => project.tasks.filter(t => t.blocked).length)
const lastUpdate = computed(() => project.log.length ? project.log[project.log.length - 1].t : '—')
const forecast = ref(null), forecastError = ref(''), forecastLoading = ref(false)
let forecastVersion = 0
async function refreshForecast() {
  const id = projects.currentProjectId
  const attempt = ++forecastVersion
  forecast.value = null; forecastError.value = ''
  if (!session.online || !id) return
  forecastLoading.value = true
  try {
    const result = await getProgressForecast(id)
    if (attempt === forecastVersion) forecast.value = result
  } catch (error) { if (attempt === forecastVersion) forecastError.value = error.message || '进度预测读取失败' }
  finally { if (attempt === forecastVersion) forecastLoading.value = false }
}
watch(() => [projects.currentProjectId, session.online], refreshForecast, { immediate: true })

/* 预测基线(US19 人工审核与回流):先固化快照,再由负责人单独采纳/放弃,之后按任务数量对账。
   基线只是记录,不写任务数据;对账口径与后端 reconciliation 字段一一对应。 */
const baselines = ref([]), baselineError = ref(''), baselineLoading = ref(false), baselineSaving = ref(false)
const VERDICT_TEXT = {
  not_baseline: '未采纳', reached: '已完成计划范围', overdue: '已逾期', within_plan: '计划内', no_date: '无计划日期'
}
function baselineLabel(verdict) { return VERDICT_TEXT[verdict] || verdict }
async function refreshBaselines() {
  const id = projects.currentProjectId
  if (!session.online || !id) { baselines.value = []; return }
  baselineLoading.value = true; baselineError.value = ''
  try {
    const result = await getProgressForecastRecords(id)
    if (projects.currentProjectId === id) baselines.value = result.records
  } catch (error) { baselineError.value = error.message || '预测基线读取失败' }
  finally { baselineLoading.value = false }
}
async function saveBaseline() {
  const id = projects.currentProjectId
  if (!id || baselineSaving.value) return
  baselineSaving.value = true; baselineError.value = ''
  try { await recordProgressForecast(id); await refreshBaselines() }
  catch (error) { baselineError.value = error.message || '固化基线失败' }
  finally { baselineSaving.value = false }
}
async function decideBaseline(record, decision) {
  const id = projects.currentProjectId
  if (!id || baselineSaving.value) return
  baselineSaving.value = true; baselineError.value = ''
  try { await decideProgressForecast(id, record.id, decision); await refreshBaselines() }
  catch (error) { baselineError.value = error.message || '基线决策失败' }
  finally { baselineSaving.value = false }
}
watch(() => [projects.currentProjectId, session.online], refreshBaselines, { immediate: true })

const efficiency = ref(null), efficiencyError = ref(''), efficiencyLoading = ref(false)
let efficiencyVersion = 0
async function refreshEfficiency() {
  const id = projects.currentProjectId
  const attempt = ++efficiencyVersion
  efficiency.value = null; efficiencyError.value = ''
  if (!session.online || !id) return
  efficiencyLoading.value = true
  try {
    const result = await getEfficiencyInsights(id)
    if (attempt === efficiencyVersion) efficiency.value = result
  } catch (error) { if (attempt === efficiencyVersion) efficiencyError.value = error.message || '效率分析读取失败' }
  finally { if (attempt === efficiencyVersion) efficiencyLoading.value = false }
}
watch(() => [projects.currentProjectId, session.online], refreshEfficiency, { immediate: true })
const improvementIssue = ref(null)
const improvementForm = ref({ owner_id: '', hours: '', week_start: '', week_end: '' })
const improvementSaving = ref(false), improvementError = ref('')
function improvementFor(issue) {
  return efficiency.value?.improvements?.find(row => row.source_task_code === issue.task_code && row.issue_kind === issue.kind)
}
function beginImprovement(issue) {
  improvementIssue.value = issue
  improvementError.value = ''
  improvementForm.value = { owner_id: String(session.currentUser?.id || ''), hours: '', week_start: '', week_end: '' }
}
async function confirmImprovement() {
  const issue = improvementIssue.value
  const id = projects.currentProjectId
  if (!issue || !id || improvementSaving.value) return
  const payload = Object.fromEntries(Object.entries(improvementForm.value).map(([key, value]) => [key, Number(value)]))
  if (!Number.isInteger(payload.owner_id) || payload.owner_id < 1 || !Number.isInteger(payload.hours)
      || payload.hours < 0 || payload.hours > 999 || !Number.isInteger(payload.week_start)
      || !Number.isInteger(payload.week_end) || payload.week_start < 1 || payload.week_end > 6
      || payload.week_start > payload.week_end || Object.values(improvementForm.value).some(value => value === '')) {
    improvementError.value = '请选择负责人并填写有效工时与第 1–6 周排期'
    return
  }
  improvementSaving.value = true; improvementError.value = ''
  try {
    await createEfficiencyImprovement(id, issue.task_code, { issue_kind: issue.kind, ...payload })
    improvementIssue.value = null
    const reloads = await Promise.allSettled([refreshEfficiency(), project.loadAll(id)])
    if (reloads.some(result => result.status === 'rejected')) improvementError.value = '改进任务已创建，页面刷新失败，请手动刷新'
  } catch (error) { improvementError.value = error.message || '改进任务创建失败' }
  finally { improvementSaving.value = false }
}
watch(() => projects.currentProjectId, () => { improvementIssue.value = null; improvementError.value = '' })

/* US22 质量分析:只读规范缺口 + 确认后建改进任务,口径与效率分析一致 */
const QUALITY_KINDS = {
  story_missing_acceptance: '补齐验收条件',
  story_missing_owner: '认领故事',
  task_without_story: '补关联故事'
}
const quality = ref(null), qualityError = ref(''), qualityLoading = ref(false)
let qualityVersion = 0
async function refreshQuality() {
  const id = projects.currentProjectId
  const attempt = ++qualityVersion
  quality.value = null; qualityError.value = ''
  if (!session.online || !id) return
  qualityLoading.value = true
  try {
    const result = await getQualityAnalysis(id)
    if (attempt === qualityVersion) quality.value = result
  } catch (error) { if (attempt === qualityVersion) qualityError.value = error.message || '质量分析读取失败' }
  finally { if (attempt === qualityVersion) qualityLoading.value = false }
}
watch(() => [projects.currentProjectId, session.online], refreshQuality, { immediate: true })
const qualityIssue = ref(null)
const qualityForm = ref({ owner_id: '', hours: '', week_start: '', week_end: '' })
const qualitySaving = ref(false), qualityFormError = ref('')
function qualityImprovementFor(finding) {
  return quality.value?.improvements?.find(row => row.source_code === finding.source_code && row.finding_kind === finding.kind)
}
function beginQuality(finding) {
  qualityIssue.value = finding
  qualityFormError.value = ''
  qualityForm.value = { owner_id: String(session.currentUser?.id || ''), hours: '', week_start: '', week_end: '' }
}
async function confirmQuality() {
  const finding = qualityIssue.value
  const id = projects.currentProjectId
  if (!finding || !id || qualitySaving.value) return
  const payload = Object.fromEntries(Object.entries(qualityForm.value).map(([key, value]) => [key, Number(value)]))
  if (!Number.isInteger(payload.owner_id) || payload.owner_id < 1 || !Number.isInteger(payload.hours)
      || payload.hours < 0 || payload.hours > 999 || !Number.isInteger(payload.week_start)
      || !Number.isInteger(payload.week_end) || payload.week_start < 1 || payload.week_end > 6
      || payload.week_start > payload.week_end || Object.values(qualityForm.value).some(value => value === '')) {
    qualityFormError.value = '请选择负责人并填写有效工时与第 1–6 周排期'
    return
  }
  qualitySaving.value = true; qualityFormError.value = ''
  try {
    await createQualityImprovement(id, finding.source_code, { finding_kind: finding.kind, ...payload })
    qualityIssue.value = null
    const reloads = await Promise.allSettled([refreshQuality(), project.loadAll(id)])
    if (reloads.some(result => result.status === 'rejected')) qualityFormError.value = '改进任务已创建，页面刷新失败，请手动刷新'
  } catch (error) { qualityFormError.value = error.message || '改进任务创建失败' }
  finally { qualitySaving.value = false }
}
watch(() => projects.currentProjectId, () => { qualityIssue.value = null; qualityError.value = '' })

/* US20 快速入口：按需读取现有团队风险，不在总览加载时触发整包画像分析。 */
const teamRisks = ref(null), riskError = ref(''), riskLoading = ref(false), riskSubmitting = ref(false), riskNotice = ref('')
let riskVersion = 0
let riskRange = null
const localDate = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
async function refreshRisks() {
  const id = projects.currentProjectId
  if (!session.online || !id || riskLoading.value) return
  const attempt = ++riskVersion
  const now = new Date()
  const range = { start: localDate(new Date(now.getFullYear(), now.getMonth(), 1)), end: localDate(now) }
  riskLoading.value = true; riskError.value = ''; riskNotice.value = ''; teamRisks.value = null; riskRange = null
  try {
    const result = await profileAgentApi.analysis(range.start, range.end, id)
    if (!Array.isArray(result?.team_risks)) throw new Error('风险分析结果格式无效')
    if (attempt === riskVersion) { teamRisks.value = result.team_risks; riskRange = range }
  } catch (error) { if (attempt === riskVersion) riskError.value = error.message || '风险分析读取失败' }
  finally { if (attempt === riskVersion) riskLoading.value = false }
}
async function submitRisks() {
  const id = projects.currentProjectId
  if (!id || !riskRange || !teamRisks.value?.length || riskSubmitting.value || session.isViewer) return
  riskSubmitting.value = true; riskError.value = ''; riskNotice.value = ''
  try {
    const ids = await profileAgentApi.submitRiskSuggestions(riskRange.start, riskRange.end, '风险预警', id)
    if (!Array.isArray(ids)) throw new Error('风险建议送审结果格式无效')
    if (id === projects.currentProjectId) riskNotice.value = `已送审 ${ids.length} 条风险建议，请到提案审核中心处理。`
  } catch (error) { if (id === projects.currentProjectId) riskError.value = error.message || '风险建议送审失败' }
  finally { riskSubmitting.value = false }
}
watch(() => projects.currentProjectId, () => { ++riskVersion; teamRisks.value = null; riskRange = null; riskError.value = ''; riskNotice.value = ''; riskLoading.value = false })

/* Sprint 分片口径统一到 MAP_SPRINTS(4 片,含 Sprint 4+):与故事地图切片、看板筛选下拉、
   后端 /api/dashboard.by_sprint 的 4 个桶一致。此前这里用的是 SPRINTS(仅 3 片)+ 按 i+1 过滤,
   导致 Sprint 4+ 的故事(sprint>=4)不在任何卡片里 —— 卡片故事数之和 34 ≠ 故事总数 37,
   而 FE-OVW-01 旧断言把「3 张卡」当成期望值钉住了该缺陷。 */
const SPRINT_WEEKS = ['W1–W2', 'W3–W4', 'W5–W6', '后续路线']
const sprintCards = computed(() => MAP_SPRINTS.map((sp, i) => {
  const list = project.stories.filter(sp.matches)
  const d = list.filter(s => s.status === 2).length
  return { name: sp.name, tag: sp.tag, cls: sp.cls, list, d, p: project.groupPct(list), weeks: SPRINT_WEEKS[i] }
}))
</script>

<template>
  <section class="view" id="view-overview">
    <div class="hero">
      <div>
        <div class="eyebrow">PROJECT OVERVIEW / 项目总览</div>
        <h1>{{ project.currentProject?.name || '我的项目' }}</h1>
        <p>{{ project.currentProject?.description || '当前项目的需求、排期与协作数据。' }}</p>
      </div>
      <button class="primary" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="go('board')">＋ 新建用户故事</button>
    </div>

    <div class="stats">
      <div class="stat"><label>故事总数</label><strong id="ov-total">{{ ovTotal }}</strong><span class="small">个用户故事</span></div>
      <div class="stat"><label>规划任务</label><strong id="ov-tasks">{{ ovTasks }}</strong><span class="small">个开发任务</span></div>
      <div class="stat"><label>团队成员</label><strong>{{ String(project.members.length).padStart(2, '0') }}</strong><span class="small">人 · 含只读查看者</span></div>
      <div class="stat"><label>迭代周期</label><strong>06</strong><span class="small">周 · 3 个 Sprint + 后续路线</span></div>
    </div>

    <div class="h-sec">实时进度 · 看板</div>
    <div id="realtime">
      <div class="stats">
        <div class="stat"><label>完成比例</label><strong>{{ realtimePct }}%</strong><span class="small">{{ doneCount }} / {{ project.stories.length }} 故事</span></div>
        <div class="stat"><label>进行中</label><strong>{{ String(doingCount).padStart(2, '0') }}</strong><span class="small">个故事</span></div>
        <div class="stat"><label>待关注 / 阻塞</label><strong>{{ String(blockedCount).padStart(2, '0') }}</strong><span class="small">个任务需确认</span></div>
        <div class="stat"><label>最近更新</label><strong class="mono" style="font-size:20px">{{ lastUpdate }}</strong><span class="small">本机变更时间</span></div>
      </div>
    </div>

    <div class="h-sec">进度预测 · 历史任务吞吐</div>
    <div class="stat" role="region" aria-label="项目进度预测">
      <button type="button" :disabled="forecastLoading || !session.online" @click="refreshForecast">刷新预测</button>
      <p v-if="!session.online">连接项目服务后可查看基于真实任务状态历史的预测。</p>
      <p v-else-if="forecastLoading" role="status">正在读取任务历史…</p>
      <p v-else-if="forecastError" role="alert">{{ forecastError }}</p>
      <template v-else-if="forecast">
        <p v-if="forecast.status === 'forecast'"><strong>预计 {{ forecast.estimated_date }}</strong> 完成当前未完成任务（约 {{ forecast.estimated_remaining_days }} 天）。</p>
        <p v-else-if="forecast.status === 'insufficient_data'">历史数据不足，暂不提供交付日期。</p>
        <p v-else-if="forecast.status === 'empty_scope'">当前项目没有任务，暂无预测范围。</p>
        <p v-else>当前任务已全部完成。</p>
        <p>未完成 {{ forecast.tasks_open }} 项；已完成 {{ forecast.tasks_done }} 项。{{ forecast.history_start }} 至 {{ forecast.history_end }} 的有效完成记录 {{ forecast.recent_completions }} 项<span v-if="forecast.status === 'forecast'">，约 {{ forecast.completions_per_week }} 项/周</span>。</p>
        <p class="small">{{ forecast.assumptions }}</p>
      </template>
    </div>

    <div class="h-sec">进度基线 · 人工确认与对账</div>
    <div class="stat" role="region" aria-label="进度预测基线">
      <button type="button" :disabled="baselineLoading || baselineSaving || !session.online || forecast?.status !== 'forecast' || session.isViewer"
              :title="session.isViewer ? READONLY_TITLE : ''" @click="saveBaseline">固化当前预测</button>
      <p v-if="!session.online">连接项目服务后可固化预测基线并查看对账。</p>
      <p v-else-if="baselineLoading" role="status">正在读取预测基线…</p>
      <p v-else-if="baselineError" role="alert">{{ baselineError }}</p>
      <template v-else>
        <p v-if="!baselines.length" class="small">尚无基线。固化当前预测后由负责人采纳，才能事后对账。</p>
        <ul v-else>
          <li v-for="record in baselines" :key="record.id">
            <strong>#{{ record.id }} · {{ record.estimated_date }}</strong>
            <span>：{{ record.requested_at }} 固化，{{ record.record_status === 'proposed' ? '待确认' : record.record_status === 'accepted' ? '已采纳' : '已放弃' }}</span>
            <span v-if="record.record_status === 'accepted'">；此后完成 {{ record.reconciliation.completed_since }} 项，剩余 {{ record.reconciliation.open_now }} 项，{{ baselineLabel(record.reconciliation.verdict) }}<span v-if="record.reconciliation.due_in_days !== null">（距计划日期 {{ record.reconciliation.due_in_days }} 天）</span></span>
            <span v-if="record.decision_note">；备注：{{ record.decision_note }}</span>
            <span v-if="record.record_status === 'proposed'">
              <button type="button" :disabled="baselineSaving || session.isViewer" @click="decideBaseline(record, 'accept')">采纳为基线</button>
              <button type="button" :disabled="baselineSaving || session.isViewer" @click="decideBaseline(record, 'discard')">放弃</button>
            </span>
          </li>
        </ul>
        <p class="small">对账只比较任务数量与计划日期；基线不修改任务数据，也不替你做排期。</p>
      </template>
    </div>

    <div class="h-sec">风险预警 · 团队事实与建议（US20）</div>
    <div class="stat" role="region" aria-label="项目风险预警">
      <button type="button" :disabled="riskLoading || !session.online" @click="refreshRisks">{{ riskLoading ? '正在分析…' : '查看本月风险' }}</button>
      <p v-if="!session.online">连接项目服务后可分析本月团队风险。</p>
      <p v-if="riskError" role="alert">{{ riskError }}</p>
      <p v-if="teamRisks && !teamRisks.length">本月未识别到团队风险；仅代表当前数据与规则的结果。</p>
      <template v-if="teamRisks?.length">
        <p>识别到 {{ teamRisks.length }} 项团队风险，提交后仍需在提案审核中心确认。</p>
        <ul><li v-for="(item, index) in teamRisks" :key="`${item.kind}-${index}`">
          <strong>{{ item.member || item.task_id || item.kind }}</strong>：{{ item.inference }}
          <span>证据：{{ item.evidence }}；影响：{{ item.impact }}；建议：{{ item.suggested_action }}</span>
        </li></ul>
        <button type="button" :disabled="riskLoading || riskSubmitting || session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="submitRisks">{{ riskSubmitting ? '正在送审…' : '将风险建议送审' }}</button>
        <button type="button" @click="go('review')">打开提案审核中心</button>
      </template>
      <p v-if="riskNotice" role="status">{{ riskNotice }}</p>
      <p class="small">仅根据当前项目数据提出预警；送审不会直接修改任务。</p>
    </div>

    <div class="h-sec">效率分析 · 任务阻塞与状态停滞</div>
    <div class="stat" role="region" aria-label="项目效率分析">
      <button type="button" :disabled="efficiencyLoading || !session.online" @click="refreshEfficiency">刷新分析</button>
      <p v-if="!session.online">连接项目服务后可查看任务瓶颈。</p>
      <p v-else-if="efficiencyLoading" role="status">正在读取任务状态…</p>
      <p v-else-if="efficiencyError" role="alert">{{ efficiencyError }}</p>
      <template v-else-if="efficiency">
        <p>当前任务 {{ efficiency.open_tasks }} 项；识别到 {{ efficiency.issues.length }} 项待复核瓶颈。</p>
        <p v-if="efficiency.missing_or_mismatched_history">其中 {{ efficiency.missing_or_mismatched_history }} 项缺少有效状态历史，无法判断是否停滞。</p>
        <p v-if="!efficiency.issues.length">暂无基于现有记录可识别的阻塞或状态停滞。</p>
        <ul v-else>
          <li v-for="issue in efficiency.issues" :key="issue.task_code">
            <strong>{{ issue.task_code }} · {{ issue.task_name }}</strong>：{{ issue.kind === 'blocked' ? '任务标记为阻塞' : `进行中状态已持续 ${issue.days_in_status} 天` }}。
            {{ issue.suggestion }}
            <span v-if="improvementFor(issue)">已确认，改进任务 {{ improvementFor(issue).improvement_task_code }}。</span>
            <button v-else type="button" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="beginImprovement(issue)">确认并创建改进任务</button>
          </li>
        </ul>
        <form v-if="improvementIssue" @submit.prevent="confirmImprovement">
          <p>确认 {{ improvementIssue.task_code }} 的{{ improvementIssue.kind === 'blocked' ? '阻塞' : '状态停滞' }}提示，创建一项管理任务：</p>
          <label>负责人 <select v-model="improvementForm.owner_id" required><option value="">请选择</option><option v-for="member in project.members" :key="member.id" :value="String(memberUserId(member.id))">{{ member.name }}</option></select></label>
          <label>工时 <input v-model="improvementForm.hours" type="number" min="0" max="999" required></label>
          <label>开始周 <input v-model="improvementForm.week_start" type="number" min="1" max="6" required></label>
          <label>结束周 <input v-model="improvementForm.week_end" type="number" min="1" max="6" required></label>
          <button type="submit" :disabled="improvementSaving">{{ improvementSaving ? '正在创建…' : '确认创建' }}</button>
          <button type="button" @click="improvementIssue = null">取消</button>
        </form>
        <p v-if="improvementError" role="alert">{{ improvementError }}</p>
        <div v-if="efficiency.improvements.length">
          <p>改进任务追踪</p>
          <ul><li v-for="record in efficiency.improvements" :key="`${record.source_task_code}-${record.issue_kind}`">
            {{ record.source_task_code }} → {{ record.improvement_task_code }}：{{ record.improvement_status === 2 ? '已完成' : record.improvement_status === 3 ? '已取消' : record.improvement_status === 1 ? '进行中' : record.improvement_status === 0 ? '待办' : '任务已不存在' }}<span v-if="record.improvement_status !== null">（{{ record.improvement_progress }}%）</span>；源瓶颈对账：{{ record.source_outcome === 'signal_cleared' ? '信号已消失（1 → 0）' : record.source_outcome === 'still_present' ? '仍存在（1 → 1）' : '数据不足，暂不判断' }}
          </li></ul>
        </div>
        <p class="small">{{ efficiency.limitations }}</p>
      </template>
    </div>

    <div class="h-sec">质量分析 · 需求与任务规范缺口（US22）</div>
    <div class="stat" role="region" aria-label="项目质量分析">
      <button type="button" :disabled="qualityLoading || !session.online" @click="refreshQuality">刷新分析</button>
      <p v-if="!session.online">连接项目服务后可查看规范缺口。</p>
      <p v-else-if="qualityLoading" role="status">正在读取故事与任务字段…</p>
      <p v-else-if="qualityError" role="alert">{{ qualityError }}</p>
      <template v-else-if="quality">
        <p>故事 {{ quality.stories_total }} 条、待办/进行中任务 {{ quality.open_tasks }} 项；识别到 {{ quality.findings.length }} 项规范缺口。</p>
        <p v-if="!quality.findings.length">暂无基于现有记录可识别的验收条件、认领或追溯缺口。</p>
        <ul v-else>
          <li v-for="finding in quality.findings" :key="`${finding.kind}-${finding.source_code}`">
            <strong>{{ finding.source_code }} · {{ finding.source_name }}</strong>：{{ finding.detail }}
            {{ finding.suggestion }}
            <span v-if="qualityImprovementFor(finding)">已确认，改进任务 {{ qualityImprovementFor(finding).improvement_task_code }}。</span>
            <button v-else type="button" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="beginQuality(finding)">确认并创建改进任务</button>
          </li>
        </ul>
        <form v-if="qualityIssue" @submit.prevent="confirmQuality">
          <p>确认 {{ qualityIssue.source_code }} 的「{{ QUALITY_KINDS[qualityIssue.kind] }}」缺口，创建一项管理任务：</p>
          <label>负责人 <select v-model="qualityForm.owner_id" required><option value="">请选择</option><option v-for="member in project.members" :key="member.id" :value="String(memberUserId(member.id))">{{ member.name }}</option></select></label>
          <label>工时 <input v-model="qualityForm.hours" type="number" min="0" max="999" required></label>
          <label>开始周 <input v-model="qualityForm.week_start" type="number" min="1" max="6" required></label>
          <label>结束周 <input v-model="qualityForm.week_end" type="number" min="1" max="6" required></label>
          <button type="submit" :disabled="qualitySaving">{{ qualitySaving ? '正在创建…' : '确认创建' }}</button>
          <button type="button" @click="qualityIssue = null">取消</button>
        </form>
        <p v-if="qualityFormError" role="alert">{{ qualityFormError }}</p>
        <div v-if="quality.improvements.length">
          <p>改进任务追踪</p>
          <ul><li v-for="record in quality.improvements" :key="`${record.source_code}-${record.finding_kind}`">
            {{ record.source_code }} → {{ record.improvement_task_code }}：{{ record.improvement_status === 2 ? '已完成' : record.improvement_status === 3 ? '已取消' : record.improvement_status === 1 ? '进行中' : record.improvement_status === 0 ? '待办' : '任务已不存在' }}<span v-if="record.improvement_status !== null">（{{ record.improvement_progress }}%）</span>
          </li></ul>
        </div>
        <p class="small">{{ quality.limitations }}</p>
        <div v-if="quality.evidence" class="evi">
          <p>真实证据（不推断质量）</p>
          <p v-if="quality.evidence.code_activity">
            代码活动：提交 {{ quality.evidence.code_activity.commits_total }} 次（近 14 天 {{ quality.evidence.code_activity.commits_last_14d }} 次）、
            PR {{ quality.evidence.code_activity.prs_total }}、评审 {{ quality.evidence.code_activity.reviews_total }}、
            测试相关提交 {{ quality.evidence.code_activity.test_commits }} 次。
            <span class="small">{{ quality.evidence.code_activity.note }}</span>
          </p>
          <p v-if="quality.evidence.ci">
            <template v-if="quality.evidence.ci.available">
              CI：最近 {{ quality.evidence.ci.runs_total }} 次已定论运行（另有 {{ quality.evidence.ci.runs_cancelled }} 次取消），最近结论 {{ quality.evidence.ci.last_conclusion }}，通过率 {{ quality.evidence.ci.pass_rate }}%。
              <span v-if="quality.evidence.ci.last_run">最新运行 #{{ quality.evidence.ci.last_run.run_number }}（{{ quality.evidence.ci.last_run.head_sha?.slice(0, 7) }}，{{ quality.evidence.ci.last_run.conclusion }}）<a v-if="quality.evidence.ci.last_run.html_url" :href="quality.evidence.ci.last_run.html_url" target="_blank" rel="noopener noreferrer">查看 CI</a>。</span>
              <span v-if="quality.evidence.ci.last_failed">历史最近失败：{{ quality.evidence.ci.last_failed.run_name }} #{{ quality.evidence.ci.last_failed.run_number }}。</span>
            </template>
            <template v-else>CI：未接入（{{ quality.evidence.ci.reason }}）。</template>
            <span class="small">{{ quality.evidence.ci.note }}</span>
          </p>
          <p v-if="quality.evidence.ci?.coverage">
            <template v-if="quality.evidence.ci.coverage.available">
              Java 后端覆盖率（JaCoCo）：指令 {{ quality.evidence.ci.coverage.instruction_pct }}%（{{ quality.evidence.ci.coverage.instruction_covered }}/{{ quality.evidence.ci.coverage.instruction_covered + quality.evidence.ci.coverage.instruction_missed }}），
              行 {{ quality.evidence.ci.coverage.line_pct }}%。
            </template>
            <template v-else>Java 后端覆盖率：未接入（{{ quality.evidence.ci.coverage.reason }}）。</template>
          </p>
          <p v-if="quality.evidence.ci?.failed_tests">
            <template v-if="quality.evidence.ci.failed_tests.available">
              历史最近失败运行的测试 {{ quality.evidence.ci.failed_tests.failed_tests_total }} 项<span v-if="quality.evidence.ci.failed_tests.run_number">（run #{{ quality.evidence.ci.failed_tests.run_number }}<span v-if="quality.evidence.ci.failed_tests.created_at"> · {{ quality.evidence.ci.failed_tests.created_at }}</span>）</span><span v-if="quality.evidence.ci.failed_tests.failed_tests?.length">：{{ quality.evidence.ci.failed_tests.failed_tests.slice(0, 5).join('；') }}</span>。
            </template>
            <template v-else>失败测试：未接入（{{ quality.evidence.ci.failed_tests.reason }}）。</template>
          </p>
          <p v-if="quality.evidence.repository">
            <template v-if="quality.evidence.repository.available">
              仓库：{{ quality.evidence.repository.repo }}（{{ quality.evidence.repository.default_branch }}）——
              {{ quality.evidence.repository.has_ci_workflow ? '有 CI 配置' : quality.evidence.repository.truncated ? 'CI 配置未在已返回的文件树中找到' : '无 CI 配置' }}、
              {{ quality.evidence.repository.has_tests ? `已发现测试文件 ${quality.evidence.repository.test_file_count} 个` : quality.evidence.repository.truncated ? '测试文件未在已返回的文件树中找到' : '无测试文件' }}、
              {{ quality.evidence.repository.has_docs ? '有文档' : quality.evidence.repository.truncated ? '文档未在已返回的文件树中找到' : '无文档目录' }}<span v-if="quality.evidence.repository.truncated">（GitHub 文件树已截断，数量只是下界）</span>。
            </template>
            <template v-else>仓库证据：未接入（{{ quality.evidence.repository.reason }}）。</template>
          </p>
        </div>
      </template>
    </div>

    <div class="h-sec">Sprint 进度 · 4 个发布切片</div>
    <div class="sprint-grid" id="sprint-grid">
      <div v-for="c in sprintCards" :key="c.name" class="sprint" :class="c.cls">
        <div class="top"><h3>{{ c.name }}</h3><span class="pct">{{ c.p }}%</span></div>
        <div class="tagline">{{ c.tag }} · {{ c.list.length }} 个故事</div>
        <div class="bar"><i :style="{ width: c.p + '%' }"></i></div>
        <div class="meta">完成 {{ c.d }} / {{ c.list.length }} · {{ c.weeks }}</div>
      </div>
    </div>

    <div class="h-sec">里程碑 · 关键交付节点</div>
    <div class="mile" id="mile-grid">
      <div v-if="!project.milestones.length" class="empty-state">暂无项目里程碑</div>
      <div v-for="m in project.milestones" :key="m.id" class="milecard" :class="{ done: m.week <= 2 }">
        <span class="wk">W{{ m.week }}</span><span class="mid">{{ m.id }}</span><h4>{{ m.name }}</h4><p>{{ m.desc }}</p>
      </div>
    </div>

    <div class="h-sec">总体完成度</div>
    <div class="stat" style="margin-bottom:14px">
      <div class="inline"><label style="margin:0">需求故事完成进度</label><b class="mono" id="ov-percent">{{ overallPct }}%</b></div>
      <div class="track" role="progressbar" aria-label="总体完成度" aria-valuemin="0" aria-valuemax="100"><i id="ov-fill" :style="{ width: overallPct + '%' }"></i></div>
    </div>

    <div class="h-sec">快速进入</div>
    <div class="quick">
      <button class="quickcard" @click="go('board')"><span class="ic">▦</span><b>故事看板</b><span>拖拽更新状态、编辑与导出</span></button>
      <button class="quickcard" @click="go('gantt')"><span class="ic">▤</span><b>甘特图</b><span>6 周排期与里程碑</span></button>
      <button class="quickcard" @click="go('ai')"><span class="ic">✦</span><b>AI 工作台</b><span>浏览智能能力与最近工作</span></button>
    </div>
  </section>
</template>
