<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { getProgressForecast, getProgressForecastRecords, recordProgressForecast, decideProgressForecast } from '@/api/progressForecast'
import { getEfficiencyInsights, createEfficiencyImprovement } from '@/api/efficiencyInsights'
import { getQualityAnalysis, createQualityImprovement, createQualityTestSet, evaluateCodeQuality, reviewSemantics } from '@/api/qualityAnalysis'
import { profileAgentApi } from '@/api/profileAgent'
import { memberUserId } from '@/data/memberIdentity'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { MAP_SPRINTS } from '@/data/seed'

/* 项目分析中心：六个专题共用一套数据逻辑，按当前激活的 tab 懒加载对应接口 */
const TABS = [
  { key: 'forecast', label: '进度预测', desc: '历史吞吐与交付日期', icon: '◷' },
  { key: 'baseline', label: '进度基线', desc: '固化快照与事后对账', icon: '⚑' },
  { key: 'risk', label: '风险预警', desc: '团队事实与改进建议', icon: '⚠' },
  { key: 'efficiency', label: '效率分析', desc: '阻塞与状态停滞排查', icon: '⚡' },
  { key: 'quality', label: '质量分析', desc: '规范缺口与交付质量', icon: '◈' },
  { key: 'sprint', label: 'Sprint 进度', desc: '4 个发布切片完成度', icon: '▦' }
]

const router = useRouter()
const route = useRoute()
const project = useProjectStore()
const projects = useProjectsStore()
const session = useSessionStore()
const go = v => router.push({ name: project.currentProjectId ? `project-${v}` : v, params: project.currentProjectId ? { projectId: project.currentProjectId } : {} })
const activeTab = computed(() => {
  const tab = String(route.query.tab || '')
  return TABS.some(item => item.key === tab) ? tab : 'forecast'
})
function selectTab(key) { router.replace({ query: { ...route.query, tab: key } }) }

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

/* US22 质量分析:三路输入(故事/任务字段、测试集、CI/仓库证据)定位缺口 + 人工确认后建改进任务 */
const QUALITY_KINDS = {
  story_missing_acceptance: '补齐验收条件',
  story_missing_owner: '认领故事',
  task_without_story: '补关联故事',
  test_missing_boundary_cases: '补齐边界用例',
  ci_pipeline_failing: '修复 CI 流水线',
  failed_tests_unresolved: '处理失败测试',
  coverage_below_threshold: '补充测试覆盖',
  repository_missing_tests: '补齐测试文件',
  repository_missing_docs: '补齐文档',
  repository_missing_ci: '接入 CI 流水线',
  code_missing_reviews: '建立代码评审',
  code_quality_dimension_gap: '修复代码质量维度',
  readme_not_actionable: '提升 README 可操作性',
  tests_not_effective: '提升测试有效性'
}
const QUALITY_SOURCES = [
  { value: 'test_case', label: '测试用例' },
  { value: 'test_code', label: '测试代码' },
  { value: 'test_report', label: '执行报告' }
]
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
    if (attempt === qualityVersion) {
      quality.value = result
      /* 首屏优先回放最近一次留档结论，避免刷新后代码质量/语义评审卡片空白 */
      if (result?.code_quality?.available) codeQuality.value = result.code_quality
      const reviews = result?.semantic_reviews || {}
      if (!semantic.value && (reviews.readme || reviews.tests)) {
        semantic.value = reviews.readme || reviews.tests
      }
    }
  } catch (error) { if (attempt === qualityVersion) qualityError.value = error.message || '质量分析读取失败' }
  finally { if (attempt === qualityVersion) qualityLoading.value = false }
}
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
/* 代码质量卡 / 语义评审卡的结论与主缺口列表同源：用同一个 source_code 让「已确认」状态互通 */
const CODE_GAP = { kind: 'code_quality_dimension_gap' }
const README_GAP = { kind: 'readme_not_actionable', source_code: 'SEM:readme' }
const TESTS_GAP = { kind: 'tests_not_effective', source_code: 'SEM:tests' }
const ACTIONABLE_SEVERITIES = ['blocking', 'high', 'medium']
const codeGap = finding => ({ ...CODE_GAP, source_code: `CQ:${finding.category}` })
const semanticGap = () => (semantic.value?.scope === 'tests' ? TESTS_GAP : README_GAP)
const semanticGapApplies = review =>
  Boolean(review?.available) && (review.reader_can_act === false || Number(review.score) < 70)
function beginDimensionGap(finding) {
  if (!finding?.category) return
  beginQuality({
    ...codeGap(finding),
    location: (codeQuality.value?.repo ? `仓库 ${codeQuality.value.repo}` : '代码质量') + ` · ${finding.title}`
  })
}
function beginSemanticGap() {
  if (!semanticGapApplies(semantic.value)) return
  const readme = semantic.value.scope !== 'tests'
  beginQuality({
    ...semanticGap(),
    location: `${readme ? 'README' : '测试代码'} · 语义评审 ${semantic.value.score} 分`
  })
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
const qualitySetForm = ref({ name: '', source: 'test_case', cases: '' })
const qualitySetSaving = ref(false), qualitySetError = ref(''), qualitySetNotice = ref('')
async function submitQualitySet() {
  const id = projects.currentProjectId
  if (!id || qualitySetSaving.value) return
  const name = qualitySetForm.value.name.trim()
  const cases = qualitySetForm.value.cases.split('\n').map(value => value.trim()).filter(Boolean)
  if (!name || !cases.length) {
    qualitySetError.value = '请填写测试集名称，并至少提交 1 条用例（每行一条）'
    return
  }
  qualitySetSaving.value = true; qualitySetError.value = ''; qualitySetNotice.value = ''
  try {
    const result = await createQualityTestSet(id, {
      name, source: qualitySetForm.value.source, cases: cases.map(value => ({ name: value }))
    })
    qualitySetNotice.value = `已提交 ${result.code}：覆盖 ${result.covered_boundaries.length} 类边界场景，`
      + (result.missing_boundaries.length
        ? `遗漏 ${result.missing_boundaries.length} 类（${result.missing_boundaries.join('、')}）。`
        : '未发现遗漏的边界类别。')
    qualitySetForm.value = { name: '', source: 'test_case', cases: '' }
    await refreshQuality()
  } catch (error) { qualitySetError.value = error.message || '测试集提交失败' }
  finally { qualitySetSaving.value = false }
}
watch(() => projects.currentProjectId, () => {
  qualityIssue.value = null; qualityError.value = ''
  qualitySetError.value = ''; qualitySetNotice.value = ''
  qualitySetForm.value = { name: '', source: 'test_case', cases: '' }
})

/* 代码质量评估：按需触发，首次约数秒（抽样拉取仓库文件），之后走 10 分钟缓存 */
const codeQuality = ref(null), cqLoading = ref(false), cqError = ref('')
async function runCodeQuality() {
  const id = projects.currentProjectId
  if (!id || cqLoading.value) return
  cqLoading.value = true; cqError.value = ''
  try {
    const result = await evaluateCodeQuality(id)
    codeQuality.value = result
    if (result && result.available === false && result.reason) cqError.value = result.reason
    else await refreshQuality()
  } catch (error) {
    cqError.value = error.message || '代码质量评估失败'
  } finally {
    cqLoading.value = false
  }
}
watch(() => projects.currentProjectId, () => { codeQuality.value = null; cqError.value = '' })

/* 语义评审：按需触发，单次约 10 秒（LLM），同文本 2 小时内走缓存 */
const semantic = ref(null), semanticLoading = ref(false), semanticError = ref(''), semanticScope = ref('')
async function runSemantic(scope) {
  const id = projects.currentProjectId
  if (!id || semanticLoading.value) return
  semanticLoading.value = true; semanticError.value = ''; semanticScope.value = scope
  try {
    const result = await reviewSemantics(id, { scope })
    semantic.value = result
    if (result && result.available === false) semanticError.value = result.reason || '语义评审未返回结果'
    else await refreshQuality()
  } catch (error) {
    semanticError.value = error.message || '语义评审失败'
  } finally {
    semanticLoading.value = false; semanticScope.value = ''
  }
}
watch(() => projects.currentProjectId, () => { semantic.value = null; semanticError.value = '' })

/* 质量卡展示用的映射：只影响样式，不改判定逻辑 */
const QUALITY_KIND_CLASS = {
  story_missing_acceptance: 'tag-orange',
  story_missing_owner: 'tag-blue',
  task_without_story: 'tag-lilac'
}
const kindClass = kind => QUALITY_KIND_CLASS[kind] || 'tag-gray'
const gradeClass = g => ({ '优': 'g-good', '良': 'g-ok', '中': 'g-mid', '差': 'g-bad' }[g] || 'g-mid')
const barClass = score => (score >= 85 ? 'bar-good' : score >= 60 ? 'bar-mid' : 'bar-bad')
const riskLabel = r => ({ blocking: '阻断', high: '高', medium: '中', low: '低' }[r] || r)
const sevLabel = v => ({ blocking: '阻断', high: '高', medium: '中', low: '低' }[v] || v)


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

/* 只有当前专题才请求对应接口：质量分析首次拉取 GitHub 证据较慢，进入分析中心不应阻塞首屏 */
watch(() => [activeTab.value, projects.currentProjectId, session.online], ([tab]) => {
  if (tab === 'forecast' || tab === 'baseline') {
    refreshForecast()
    refreshBaselines()
  } else if (tab === 'efficiency') refreshEfficiency()
  else if (tab === 'quality') refreshQuality()
}, { immediate: true })
</script>

<template>
  <section class="view" id="view-insights">
    <div class="hero insight-hero">
      <div>
        <div class="eyebrow">PROJECT INSIGHTS / 项目分析中心</div>
        <h1>项目分析中心</h1>
        <p>基于当前项目的真实记录，按专题查看进度、风险、效率与质量分析。</p>
      </div>
      <button class="primary" @click="go('overview')">← 返回项目总览</button>
    </div>

    <div class="insight-layout">
      <nav class="insight-nav" aria-label="分析专题导航">
        <button v-for="item in TABS" :key="item.key" type="button" class="insight-navitem"
                :class="{ active: activeTab === item.key }" @click="selectTab(item.key)">
          <span class="insight-icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="insight-navtext"><b>{{ item.label }}</b><small>{{ item.desc }}</small></span>
          <span class="insight-arrow" aria-hidden="true">›</span>
        </button>
      </nav>

      <div class="insight-main">
        <section v-if="activeTab === 'forecast'" class="insight-panel" role="region" aria-label="项目进度预测">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">FORECAST</span>
              <h2>进度预测</h2>
              <p>根据历史任务吞吐，估算当前未完成任务的完成日期。</p>
            </div>
            <button type="button" class="qa-btn" :disabled="forecastLoading || !session.online" @click="refreshForecast">刷新预测</button>
          </header>
          <div class="insight-panel-body">
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
        </section>

        <section v-else-if="activeTab === 'baseline'" class="insight-panel" role="region" aria-label="进度预测基线">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">BASELINE</span>
              <h2>进度基线</h2>
              <p>固化预测快照，由负责人采纳后再按任务数量与计划日期对账。</p>
            </div>
            <button type="button" class="qa-btn" :disabled="baselineLoading || baselineSaving || !session.online || forecast?.status !== 'forecast' || session.isViewer"
                    :title="session.isViewer ? READONLY_TITLE : ''" @click="saveBaseline">固化当前预测</button>
          </header>
          <div class="insight-panel-body">
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
        </section>

        <section v-else-if="activeTab === 'risk'" class="insight-panel" role="region" aria-label="项目风险预警">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">RISK</span>
              <h2>风险预警</h2>
              <p>按本月团队活动识别风险事实，人工确认后可送交提案审核。</p>
            </div>
            <button type="button" class="qa-btn" :disabled="riskLoading || !session.online" @click="refreshRisks">{{ riskLoading ? '正在分析…' : '查看本月风险' }}</button>
          </header>
          <div class="insight-panel-body">
      <p v-if="!session.online">连接项目服务后可分析本月团队风险。</p>
      <p v-if="riskError" role="alert">{{ riskError }}</p>
      <p v-if="teamRisks && !teamRisks.length">本月未识别到团队风险。</p>
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
          </div>
        </section>

        <section v-else-if="activeTab === 'efficiency'" class="insight-panel" role="region" aria-label="项目效率分析">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">EFFICIENCY</span>
              <h2>效率分析</h2>
              <p>排查任务阻塞与状态停滞，确认后可创建改进任务。</p>
            </div>
            <button type="button" class="qa-btn" :disabled="efficiencyLoading || !session.online" @click="refreshEfficiency">刷新分析</button>
          </header>
          <div class="insight-panel-body">
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
        </section>

        <section v-else-if="activeTab === 'quality'" class="insight-panel" role="region" aria-label="项目质量分析">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">QUALITY</span>
              <h2>质量分析</h2>
              <p>定位需求规范、测试覆盖与代码/文档质量中的可执行缺口。</p>
            </div>
            <button type="button" class="qa-btn" :disabled="qualityLoading || !session.online" @click="refreshQuality">{{ qualityLoading ? '读取中…' : '刷新分析' }}</button>
          </header>
          <div class="insight-panel-body">
      <p v-if="!session.online" class="qa-note">连接项目服务后可查看规范缺口。</p>
      <p v-else-if="qualityLoading" class="qa-note" role="status">正在读取库内字段与 GitHub 证据（CI 运行记录与仓库文件树，首次约 20–40 秒，5 分钟内走缓存）…</p>
      <p v-else-if="qualityError" class="qa-note err" role="alert">{{ qualityError }}</p>

      <template v-else-if="quality">
        <div class="qa-metrics">
          <div class="qa-metric"><b>{{ quality.stories_total }}</b><span>故事</span></div>
          <div class="qa-metric"><b>{{ quality.open_tasks }}</b><span>待办 / 进行中任务</span></div>
          <div class="qa-metric" :class="{ alert: quality.findings.length }"><b>{{ quality.findings.length }}</b><span>质量缺口</span></div>
        </div>
        <p class="qa-lead">识别到 <b>{{ quality.findings.length }}</b> 项质量缺口（规范、测试集与代码/文档/测试内容质量）。</p>

        <ul v-if="quality.findings.length" class="qa-findings">
          <li v-for="finding in quality.findings" :key="finding.kind + finding.source_code" class="qa-finding">
            <span class="qa-tag" :class="kindClass(finding.kind)">{{ QUALITY_KINDS[finding.kind] }}</span>
            <div class="qa-finding-body">
              <p class="qa-finding-title"><span v-if="finding.category">［{{ finding.category }}］</span><b>{{ finding.location || `${finding.source_code} · ${finding.source_name}` }}</b>：{{ finding.detail }}</p>
              <p class="qa-finding-tip">{{ finding.suggestion }}</p>
            </div>
            <span v-if="qualityImprovementFor(finding)" class="qa-done">已确认 · {{ qualityImprovementFor(finding).improvement_task_code }}</span>
            <button v-else type="button" class="qa-btn ghost" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="beginQuality(finding)">确认并创建改进任务</button>
          </li>
        </ul>
        <p v-else class="qa-note">暂无基于现有记录、测试集与证据可识别的质量缺口。</p>

        <form class="qa-form" @submit.prevent="submitQualitySet">
          <p class="qa-form-title">提交测试集（测试用例/测试代码/执行报告），检查遗漏的边界场景</p>
          <label>测试集名称 <input v-model="qualitySetForm.name" type="text" maxlength="200" required placeholder="例如：登录接口回归用例"></label>
          <label>来源 <select v-model="qualitySetForm.source"><option v-for="item in QUALITY_SOURCES" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
          <label>用例（每行一条）<textarea v-model="qualitySetForm.cases" rows="4" required placeholder="空密码登录应被拒绝&#10;连续 5 次失败登录应锁定账号"></textarea></label>
          <button type="submit" class="qa-btn" :disabled="qualitySetSaving || session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''">{{ qualitySetSaving ? '正在分析…' : '提交并分析' }}</button>
        </form>
        <p v-if="qualitySetError" class="qa-note err" role="alert">{{ qualitySetError }}</p>
        <p v-if="qualitySetNotice" class="qa-note" role="status">{{ qualitySetNotice }}</p>
        <div v-if="quality.test_quality?.sets_total" class="qa-track">
          <p class="qa-sub">已提交测试集 {{ quality.test_quality.sets_total }} 份、用例 {{ quality.test_quality.cases_total }} 条；边界类别共 {{ quality.test_quality.boundaries_total }} 类。</p>
          <ul><li v-for="set in quality.test_quality.sets" :key="set.code">
            {{ set.code }} · {{ set.name }}：{{ set.total }} 条用例，覆盖 {{ set.covered_boundaries.length }}/{{ quality.test_quality.boundaries_total }} 类边界<span v-if="set.missing_boundaries.length">，遗漏 {{ set.missing_boundaries.join('、') }}</span><span v-else>，边界场景完整</span>。
          </li></ul>
        </div>

        <form v-if="qualityIssue" class="qa-form" @submit.prevent="confirmQuality">
          <p class="qa-form-title">确认 {{ qualityIssue.source_code }} 的「{{ QUALITY_KINDS[qualityIssue.kind] }}」缺口，创建管理任务</p>
          <label>负责人 <select v-model="qualityForm.owner_id" required><option value="">请选择</option><option v-for="member in project.members" :key="member.id" :value="String(memberUserId(member.id))">{{ member.name }}</option></select></label>
          <label>工时 <input v-model="qualityForm.hours" type="number" min="0" max="999" required></label>
          <label>开始周 <input v-model="qualityForm.week_start" type="number" min="1" max="6" required></label>
          <label>结束周 <input v-model="qualityForm.week_end" type="number" min="1" max="6" required></label>
          <button type="submit" class="qa-btn" :disabled="qualitySaving">{{ qualitySaving ? '正在创建…' : '确认创建' }}</button>
          <button type="button" class="qa-btn ghost" @click="qualityIssue = null">取消</button>
        </form>
        <p v-if="qualityFormError" class="qa-note err" role="alert">{{ qualityFormError }}</p>

        <div v-if="quality.improvements.length" class="qa-track">
          <p class="qa-sub">改进任务追踪</p>
          <ul>
            <li v-for="record in quality.improvements" :key="record.source_code + record.finding_kind">
              <span class="qa-track-code">{{ record.source_code }} → {{ record.improvement_task_code }}：{{ record.improvement_status === 2 ? '已完成' : record.improvement_status === 3 ? '已取消' : record.improvement_status === 1 ? '进行中' : record.improvement_status === 0 ? '待办' : '任务已不存在' }}<span v-if="record.improvement_status !== null">（{{ record.improvement_progress }}%）</span></span>
            </li>
          </ul>
        </div>

        <section class="qa-score">
          <header class="qa-score-head">
            <div>
              <h4>代码质量评估</h4>
              <p>10 维度静态扫描 · 抽样排查，非全仓审计</p>
            </div>
            <button type="button" class="qa-btn" :disabled="cqLoading || !session.online" @click="runCodeQuality">{{ cqLoading ? '正在拉取仓库…' : (codeQuality && codeQuality.available ? '重新评估' : '评估代码质量') }}</button>
          </header>

          <p v-if="cqError && !codeQuality" class="qa-note err" role="alert">{{ cqError }}</p>

          <template v-if="codeQuality && codeQuality.available">
            <div class="qa-score-body">
              <div class="qa-ring" :class="gradeClass(codeQuality.grade)" :style="{ '--p': (codeQuality.total || 0) + '%' }">
                <b>{{ codeQuality.total }}</b><span>分</span>
              </div>
              <div class="qa-score-info">
                <div class="qa-badges">
                  <span class="qa-grade" :class="gradeClass(codeQuality.grade)">{{ codeQuality.grade }}</span>
                  <span class="qa-risk" :class="'risk-' + codeQuality.risk_level">风险 {{ riskLabel(codeQuality.risk_level) }}</span>
                  <span v-if="codeQuality.persisted" class="qa-risk">已留档</span>
                </div>
                <p class="qa-verdict">{{ codeQuality.verdict }}</p>
                <p class="qa-sample">{{ codeQuality.sample_note }}</p>
              </div>
            </div>

            <div v-if="codeQuality.dimensions && codeQuality.dimensions.length" class="qa-dims">
              <div v-for="d in codeQuality.dimensions" :key="d.key" class="qa-dim">
                <span class="qa-dim-name">{{ d.name }}</span>
                <div class="qa-bar"><i :class="barClass(d.score)" :style="{ width: d.score + '%' }"></i></div>
                <b class="qa-dim-score">{{ d.score }}</b>
              </div>
            </div>

            <ul v-if="codeQuality.findings && codeQuality.findings.length" class="qa-issues">
              <li v-for="f in codeQuality.findings.slice(0, 4)" :key="f.category + '|' + f.title" class="qa-issue">
                <span class="qa-sev" :class="'sev-' + f.severity">{{ sevLabel(f.severity) }}</span>
                <div class="qa-issue-body">
                  <p class="qa-issue-title">{{ f.title }}</p>
                  <p class="qa-issue-line"><em>影响</em>{{ f.impact }}</p>
                  <p class="qa-issue-line"><em>改法</em>{{ f.action }}<span class="qa-gain">+{{ f.recoverable_points }} 分</span></p>
                  <p class="qa-issue-act">
                    <span v-if="f.category && qualityImprovementFor(codeGap(f))" class="qa-done">已确认 · {{ qualityImprovementFor(codeGap(f)).improvement_task_code }}</span>
                    <button v-else-if="f.category && ACTIONABLE_SEVERITIES.includes(f.severity)" type="button" class="qa-btn ghost" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="beginDimensionGap(f)">确认并创建改进任务</button>
                  </p>
                </div>
              </li>
            </ul>

            <p v-if="codeQuality.strengths && codeQuality.strengths.length" class="qa-strong">达标维度：{{ codeQuality.strengths.join('、') }}</p>
            <p class="qa-fine">{{ codeQuality.limitations }}</p>
          </template>
        </section>

        <section class="qa-sem">
          <header class="qa-score-head">
            <div>
              <h4>语义评审 <span class="qa-llm">LLM</span></h4>
              <p>判断「读者能否照着做」——静态规则分不出完整与空话，这一层可以</p>
            </div>
            <div class="qa-sem-actions">
              <button type="button" class="qa-btn" :disabled="semanticLoading || !session.online" @click="runSemantic('readme')">{{ semanticLoading && semanticScope === 'readme' ? '评审中…' : '评 README' }}</button>
              <button type="button" class="qa-btn" :disabled="semanticLoading || !session.online" @click="runSemantic('tests')">{{ semanticLoading && semanticScope === 'tests' ? '评审中…' : '评测试代码' }}</button>
            </div>
          </header>

          <p v-if="semanticError" class="qa-note err" role="alert">{{ semanticError }}</p>

          <template v-if="semantic && semantic.available">
            <div class="qa-score-body">
              <div class="qa-ring" :class="gradeClass(semantic.grade)" :style="{ '--p': (semantic.score || 0) + '%' }">
                <b>{{ semantic.score }}</b><span>分</span>
              </div>
              <div class="qa-score-info">
                <div class="qa-badges">
                  <span class="qa-grade" :class="gradeClass(semantic.grade)">{{ semantic.grade }}</span>
                  <span class="qa-risk">置信 {{ riskLabel(semantic.confidence) }}</span>
                  <span class="qa-risk">{{ semantic.scope === 'readme' ? 'README' : '测试代码' }}</span>
                  <span v-if="semantic.cached" class="qa-risk">缓存</span>
                  <span v-if="semantic.persisted" class="qa-risk">已留档</span>
                </div>
                <p class="qa-verdict">{{ semantic.verdict }}</p>
                <p class="qa-sample">
                  可照做：<b>{{ semantic.reader_can_act ? '是' : '否' }}</b>
                  <span v-if="semantic.unverified_quotes">　未在原文核实的引用：{{ semantic.unverified_quotes }} 条</span>
                  <span v-if="semantic.input_truncated">　文本已截断</span>
                </p>
              </div>
            </div>

            <ul v-if="semantic.issues && semantic.issues.length" class="qa-issues">
              <li v-for="(item, i) in semantic.issues" :key="'si' + i" class="qa-issue">
                <span class="qa-sev" :class="item.verified ? 'sev-medium' : 'sev-low'">{{ item.verified ? '原文' : '未核实' }}</span>
                <div class="qa-issue-body">
                  <p class="qa-issue-quote">「{{ item.quote }}」</p>
                  <p class="qa-issue-line"><em>问题</em>{{ item.problem }}</p>
                  <p v-if="item.suggestion" class="qa-issue-line"><em>改法</em>{{ item.suggestion }}</p>
                </div>
              </li>
            </ul>

            <div v-if="semantic.missing && semantic.missing.length" class="qa-missing">
              <p class="qa-sub">读者需要但文中没有</p>
              <ul class="qa-chips">
                <li v-for="(m, i) in semantic.missing" :key="'sm' + i">{{ m }}</li>
              </ul>
            </div>

            <p v-if="semantic.strengths && semantic.strengths.length" class="qa-strong">
              可保留：{{ semantic.strengths[0].why }}
            </p>
            <p v-if="semanticGapApplies(semantic)" class="qa-issue-act">
              <span v-if="qualityImprovementFor(semanticGap())" class="qa-done">已确认 · {{ qualityImprovementFor(semanticGap()).improvement_task_code }}</span>
              <button v-else type="button" class="qa-btn ghost" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="beginSemanticGap">确认并创建改进任务</button>
            </p>
            <p class="qa-fine">{{ semantic.limitations }}</p>
          </template>
        </section>

        <div v-if="quality.evidence" class="evi">
          <p>相关记录</p>
          <p v-if="quality.evidence.code_activity">
            代码活动：提交 {{ quality.evidence.code_activity.commits_total }} 次（近 14 天 {{ quality.evidence.code_activity.commits_last_14d }} 次）、
            PR {{ quality.evidence.code_activity.prs_total }}、评审 {{ quality.evidence.code_activity.reviews_total }}、
            测试相关提交 {{ quality.evidence.code_activity.test_commits }} 次。
            <span class="small">{{ quality.evidence.code_activity.note }}</span>
          </p>
          <p v-if="quality.evidence.ci">
            <template v-if="quality.evidence.ci.available">
              CI：最近 {{ quality.evidence.ci.runs_total }} 次已定论运行（另有 {{ quality.evidence.ci.runs_cancelled }} 次取消），最近结论 {{ quality.evidence.ci.last_conclusion }}，通过率 {{ quality.evidence.ci.pass_rate }}%。
              <span v-if="quality.evidence.ci.last_run">最近已完成运行 #{{ quality.evidence.ci.last_run.run_number }}（{{ quality.evidence.ci.last_run.head_sha?.slice(0, 7) }}，{{ quality.evidence.ci.last_run.conclusion }}）<a v-if="quality.evidence.ci.last_run.html_url" :href="quality.evidence.ci.last_run.html_url" target="_blank" rel="noopener noreferrer">查看 CI</a>。</span>
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
<p class="qa-fine">{{ quality.limitations }}</p>
      </template>
      <!-- 既没在线、也没在加载、也没报错，却还没有结果：给出可执行的下一步，别让卡片只剩标题 -->
      <p v-else class="qa-note">
        {{ projects.currentProjectId ? '尚未读取分析结果，点右上角「刷新分析」开始（首次需拉取 GitHub 证据，约 20–40 秒）。' : '请先在左上角选择项目，再点「刷新分析」。' }}
      </p>
          </div>
        </section>

        <section v-else-if="activeTab === 'sprint'" class="insight-panel" role="region" aria-label="项目 Sprint 进度">
          <header class="insight-panel-head">
            <div>
              <span class="insight-kicker">SPRINT</span>
              <h2>Sprint 进度</h2>
              <p>按 {{ sprintCards.length }} 个发布切片统计故事完成情况与时间窗口。</p>
            </div>
          </header>
          <div class="insight-panel-body">
            <div class="sprint-grid" id="sprint-grid">
              <div v-for="c in sprintCards" :key="c.name" class="sprint" :class="c.cls">
                <div class="top"><h3>{{ c.name }}</h3><span class="pct">{{ c.p }}%</span></div>
                <div class="tagline">{{ c.tag }} · {{ c.list.length }} 个故事</div>
                <div class="bar"><i :style="{ width: c.p + '%' }"></i></div>
                <div class="meta">完成 {{ c.d }} / {{ c.list.length }} · {{ c.weeks }}</div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.qa{border:2px solid var(--ink);background:var(--paper);box-shadow:var(--shadow);padding:18px 18px 16px;margin-top:4px}
.qa-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding-bottom:12px;border-bottom:2px dashed var(--line)}
.qa-head-l{display:flex;gap:10px;align-items:center}
.qa-chip{font-family:var(--mono);font-size:11px;letter-spacing:1px;border:2px solid var(--ink);background:var(--green);padding:3px 7px}
.qa-head h3{margin:0;font-size:18px;line-height:1.2}
.qa-head p{margin:3px 0 0;font-size:12px;color:var(--muted)}
.qa-btn{font:inherit;font-size:12px;border:2px solid var(--ink);background:var(--paper);padding:6px 11px;cursor:pointer;box-shadow:2px 2px 0 var(--ink);transition:transform .08s,box-shadow .08s;white-space:nowrap}
.qa-btn:hover:not(:disabled){transform:translate(1px,1px);box-shadow:1px 1px 0 var(--ink)}
.qa-btn:disabled{opacity:.45;cursor:not-allowed;box-shadow:none;transform:none}
.qa-btn.ghost{background:transparent;box-shadow:none;font-size:11px;padding:4px 9px}
.qa-btn.ghost:hover:not(:disabled){background:var(--paper-2)}

.qa-metrics{display:grid;grid-template-columns:repeat(3,1fr);margin:14px 0 8px;border:2px solid var(--ink);background:var(--paper-2)}
.qa-metric{padding:10px 14px;border-right:1px dashed var(--line)}
.qa-metric:last-child{border-right:0}
.qa-metric b{display:block;font-family:var(--mono);font-size:26px;line-height:1}
.qa-metric span{display:block;margin-top:4px;font-size:11px;color:var(--muted)}
.qa-metric.alert{background:#fbeee8}
.qa-metric.alert b{color:#a8482f}

.qa-lead{margin:0 0 10px;font-size:13px}
.qa-lead b{font-family:var(--mono)}

.qa-findings{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}
.qa-finding{display:flex;gap:10px;align-items:flex-start;border:2px solid var(--ink);background:#fff;padding:10px 12px 10px 15px;position:relative}
.qa-finding::before{content:"";position:absolute;left:0;top:0;bottom:0;width:5px;background:var(--orange-2)}
.qa-tag{font-family:var(--mono);font-size:10px;letter-spacing:.5px;border:1.5px solid var(--ink);padding:2px 6px;white-space:nowrap;flex:0 0 auto}
.tag-orange{background:var(--orange)}
.tag-blue{background:var(--blue)}
.tag-lilac{background:var(--lilac)}
.tag-gray{background:var(--gray)}
.qa-finding-body{flex:1;min-width:0}
.qa-finding-title{margin:0;font-size:13px;line-height:1.5}
.qa-finding-tip{margin:3px 0 0;font-size:12px;color:var(--muted)}
.qa-done{font-size:11px;color:#2f7a4f;border:1.5px dashed #7fb894;padding:2px 7px;white-space:nowrap;flex:0 0 auto}

.qa-form{display:flex;flex-wrap:wrap;align-items:flex-end;gap:8px 12px;margin-top:12px;border:2px dashed var(--line);background:var(--paper-2);padding:12px}
.qa-form-title{flex-basis:100%;margin:0;font-size:12px;color:var(--ink-soft)}
.qa-form label{display:flex;flex-direction:column;gap:3px;font-size:11px;color:var(--muted)}
.qa-form input,.qa-form select{font:inherit;font-size:12px;border:2px solid var(--ink);background:#fff;padding:4px 6px;width:96px}
.qa-form select{width:120px}

.qa-track{margin-top:12px}
.qa-sub{margin:0 0 6px;font-family:var(--mono);font-size:11px;letter-spacing:1px;color:var(--muted);text-transform:uppercase}
.qa-track ul{list-style:none;margin:0;padding:0;display:flex;flex-wrap:wrap;gap:6px}
.qa-track li{border:1.5px solid var(--line);background:#fff;padding:3px 8px;font-size:11px}
.qa-track-code{font-family:var(--mono)}

.qa-score{margin-top:16px;border:2px solid var(--ink);background:#fff}
.qa-score-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:10px 12px;background:var(--paper-2);border-bottom:2px solid var(--ink)}
.qa-score-head h4{margin:0;font-size:14px}
.qa-score-head p{margin:2px 0 0;font-size:11px;color:var(--muted)}
.qa-score-body{display:flex;gap:16px;padding:14px 12px 6px;align-items:center}
.qa-ring{--p:0%;width:92px;height:92px;border-radius:50%;border:2px solid var(--ink);background:conic-gradient(var(--green-2) var(--p),var(--paper-2) 0);display:flex;flex-direction:column;align-items:center;justify-content:center;position:relative;flex:0 0 auto}
.qa-ring::before{content:"";position:absolute;inset:9px;border-radius:50%;background:var(--paper);border:1px solid var(--line)}
.qa-ring b{position:relative;font-family:var(--mono);font-size:28px;line-height:1}
.qa-ring span{position:relative;font-size:10px;color:var(--muted)}
.qa-ring.g-ok{background:conic-gradient(var(--green) var(--p),var(--paper-2) 0)}
.qa-ring.g-mid{background:conic-gradient(var(--orange-2) var(--p),var(--paper-2) 0)}
.qa-ring.g-bad{background:conic-gradient(var(--red) var(--p),var(--paper-2) 0)}
.qa-score-info{flex:1;min-width:0}
.qa-badges{display:flex;gap:6px;margin-bottom:6px}
.qa-grade{font-family:var(--mono);font-size:11px;border:1.5px solid var(--ink);padding:2px 8px;background:var(--green)}
.qa-grade.g-ok{background:var(--green)}
.qa-grade.g-mid{background:var(--orange)}
.qa-grade.g-bad{background:var(--red)}
.qa-risk{font-size:11px;border:1.5px dashed var(--line);padding:2px 8px;color:var(--ink-soft)}
.qa-risk.risk-blocking{border-color:#c06a52;color:#a8482f;background:#fbeee8}
.qa-risk.risk-high{border-color:#c99a4e;color:#8a5a1c;background:#fdf3e3}
.qa-verdict{margin:0 0 4px;font-size:12.5px;line-height:1.6}
.qa-sample{margin:0;font-size:11px;color:var(--muted)}

.qa-dims{display:grid;grid-template-columns:repeat(2,1fr);gap:7px 18px;padding:8px 12px 12px}
.qa-dim{display:grid;grid-template-columns:76px 1fr 30px;align-items:center;gap:8px;font-size:11px}
.qa-dim-name{color:var(--muted)}
.qa-bar{height:8px;background:var(--paper-2);border:1px solid var(--line);position:relative;overflow:hidden}
.qa-bar i{position:absolute;left:0;top:0;bottom:0;background:var(--green-2)}
.qa-bar i.bar-mid{background:var(--orange-2)}
.qa-bar i.bar-bad{background:var(--red)}
.qa-dim-score{font-family:var(--mono);text-align:right}

.qa-issues{list-style:none;margin:0;padding:0 12px 12px;display:flex;flex-direction:column;gap:8px}
.qa-issue{display:flex;gap:10px;padding:8px 10px;border:1.5px dashed var(--line);background:var(--paper)}
.qa-sev{font-family:var(--mono);font-size:10px;padding:2px 7px;border:1.5px solid var(--ink);white-space:nowrap;height:fit-content}
.sev-blocking{background:var(--red)}
.sev-high{background:var(--orange)}
.sev-medium{background:var(--yellow)}
.sev-low{background:var(--gray)}
.qa-issue-body{flex:1;min-width:0}
.qa-issue-title{margin:0 0 3px;font-size:12.5px;font-weight:700}
.qa-issue-line{margin:2px 0;font-size:11.5px;color:var(--ink-soft);line-height:1.55}
.qa-issue-line em{font-style:normal;font-family:var(--mono);font-size:10px;color:var(--muted);border:1px solid var(--line);padding:0 4px;margin-right:6px}
.qa-gain{font-family:var(--mono);font-size:10px;color:#2f7a4f;border:1px dashed #7fb894;padding:0 4px;margin-left:6px;white-space:nowrap}
.qa-strong{margin:0 12px 10px;font-size:11.5px;color:#2f7a4f}

.qa-evi{margin-top:16px;padding-top:12px;border-top:2px dashed var(--line)}
.qa-evi-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
.qa-evi-col{border:1.5px solid var(--line);background:#fff;padding:8px 10px;min-width:0}
.qa-evi-col h5{margin:0 0 4px;font-family:var(--mono);font-size:10px;letter-spacing:1px;color:var(--muted);text-transform:uppercase}
.qa-evi-col p{margin:2px 0;font-size:11px;line-height:1.55;word-break:break-word}
.qa-evi-col .small{color:var(--muted);font-size:10.5px}

.qa-note{margin:10px 0;font-size:12px;color:var(--muted)}
.qa-note.err{color:#a8482f}
.qa-fine{margin:12px 0 0;font-size:10.5px;line-height:1.6;color:var(--muted);border-top:1px dashed var(--line);padding-top:8px}

@media (max-width: 980px){
  .qa-evi-grid{grid-template-columns:1fr}
  .qa-dims{grid-template-columns:1fr}
  .qa-metrics{grid-template-columns:repeat(3,1fr)}
}

.evi { margin-top: 16px; padding-top: 12px; border-top: 2px dashed var(--line); }
.evi > p { margin: 4px 0; font-size: 11px; line-height: 1.6; color: var(--ink-soft); word-break: break-word; }
.evi > p:first-child { font-family: var(--mono); font-size: 10px; letter-spacing: 1px; color: var(--muted); text-transform: uppercase; }
.evi .small { font-size: 10.5px; color: var(--muted); }
.evi a { color: #2f6f4f; border-bottom: 1px dashed currentColor; margin-left: 4px; }

.qa-sem { margin-top: 16px; border: 2px solid var(--ink); background: #fff; }
.qa-llm { font-family: var(--mono); font-size: 9px; border: 1.5px solid var(--ink); background: var(--lilac); padding: 0 5px; margin-left: 6px; vertical-align: 2px; }
.qa-sem-actions { display: flex; gap: 8px; }
.qa-issue-quote { margin: 0 0 4px; font-size: 12px; color: var(--ink); background: var(--paper-2); border-left: 3px solid var(--orange-2); padding: 3px 8px; }
.qa-missing { padding: 0 12px 12px; }
.qa-chips { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 6px; }
.qa-chips li { font-size: 11px; border: 1.5px dashed #c99a4e; background: #fdf3e3; color: #8a5a1c; padding: 2px 8px; }
.qa-issue-act { margin: 6px 0 0; display: flex; }

/* 分析中心：左侧专题导航固定，右侧只渲染当前专题面板 */
.insight-hero { align-items: center; }
.insight-layout { display: grid; grid-template-columns: 266px minmax(0, 1fr); gap: 18px; align-items: start; margin-top: 22px; }
.insight-nav { position: sticky; top: 18px; display: flex; flex-direction: column; gap: 8px; }
.insight-navitem { display: grid; grid-template-columns: 34px minmax(0, 1fr) 14px; align-items: center; gap: 10px; text-align: left; font: inherit; color: var(--ink); padding: 10px 11px; border: 2px solid var(--ink); background: var(--paper); box-shadow: 3px 3px 0 var(--ink); cursor: pointer; transition: transform .08s, box-shadow .08s, background .12s; }
.insight-navitem:hover { transform: translate(1px, 1px); box-shadow: 2px 2px 0 var(--ink); background: var(--paper-2); }
.insight-navitem.active { background: var(--green); transform: translate(2px, 2px); box-shadow: 1px 1px 0 var(--ink); }
.insight-icon { display: grid; place-items: center; width: 34px; height: 34px; border: 2px solid var(--ink); background: #fff; font-family: var(--mono); font-size: 17px; line-height: 1; }
.insight-navitem.active .insight-icon { background: var(--ink); color: var(--green); }
.insight-navtext { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.insight-navtext b { font-size: 13.5px; line-height: 1.3; }
.insight-navtext small { font-family: var(--mono); font-size: 10.5px; line-height: 1.4; color: var(--muted); }
.insight-navitem.active .insight-navtext small { color: var(--ink-soft); }
.insight-arrow { justify-self: end; font-family: var(--mono); font-size: 16px; color: var(--muted); }
.insight-navitem.active .insight-arrow { color: var(--ink); }

.insight-main { min-width: 0; }
.insight-panel { border: 2px solid var(--ink); background: var(--paper); box-shadow: var(--shadow); }
.insight-panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 15px 18px 14px; border-bottom: 2px solid var(--ink); background: var(--paper-2); }
.insight-kicker { display: inline-block; font-family: var(--mono); font-size: 10px; letter-spacing: 2px; color: var(--muted); }
.insight-panel-head h2 { margin: 2px 0 5px; font-size: 22px; line-height: 1.15; }
.insight-panel-head p { margin: 0; max-width: 56ch; font-size: 12.5px; line-height: 1.55; color: var(--ink-soft); }
.insight-panel-body { padding: 16px 18px 18px; font-size: 13px; line-height: 1.65; }
.insight-panel-body > p { margin: 6px 0; }
.insight-panel-body ul { margin: 10px 0; padding-left: 18px; }
.insight-panel-body li { margin: 4px 0; }
.insight-panel-body .small { font-size: 11.5px; color: var(--muted); }
.insight-panel-body .qa-note:first-child { margin-top: 0; }

@media (max-width: 980px){
  .insight-layout { grid-template-columns: 1fr; }
  .insight-nav { position: static; flex-direction: row; overflow-x: auto; padding-bottom: 4px; }
  .insight-navitem { flex: 0 0 auto; grid-template-columns: 30px minmax(0, 1fr); min-width: 208px; }
  .insight-icon { width: 30px; height: 30px; font-size: 15px; }
  .insight-arrow { display: none; }
}
@media (max-width: 560px){
  .insight-panel-head { flex-direction: column; align-items: stretch; }
}
</style>
