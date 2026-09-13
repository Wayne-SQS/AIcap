<script setup>
import { computed, ref } from 'vue'
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { profileAgentApi } from '@/api/profileAgent'

/* AI 任务提交与成员能力画像智能体面板
   - 时间范围选择(文档 4.9):默认本月,可自定义起止日期
   - 成员工作摘要(4.3):事实层 work_items + AI 推断层 ai_inference
   - 动态能力画像(4.7):客观事实与 AI 推断区分展示
   - 团队风险(4.8):一键送审统一 AI 建议审核中心
   - 全部结论带证据;数据不足时如实显示,不编造 */

const meeting = useMeetingStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()

const today = new Date()
const monthStart = new Date(today.getFullYear(), today.getMonth(), 1)
const iso = d => d.toISOString().slice(0, 10)
const rangeStart = ref(iso(monthStart))
const rangeEnd = ref(iso(today))

const loading = ref(false)
const running = ref(false)
const result = ref(null)
const errorMsg = ref('')
const activeMember = ref(null)
const difficultyTab = ref(false)
const difficulties = ref([])
const heatmapRows = ref([])
const selectedDay = ref(null)
const selectedDayActs = ref([])
const compareOpen = ref(false)
const compareLoading = ref(false)
const compareResult = ref(null)
const prevStart = ref('')
const prevEnd = ref('')

const members = computed(() => (result.value?.members || []))
const risks = computed(() => (result.value?.team_risks || []))
const currentMember = computed(() =>
  members.value.find(m => m.user_id === activeMember.value) || members.value[0] || null)

const HEAT_COLORS = ['#e8e8e8', '#c8e6c9', '#81c784', '#4caf50', '#1b5e20']
const heatLevel = total => (total >= 4 ? 4 : total >= 2 ? 3 : total >= 1 ? 2 : 0)

function fmtRange() {
  return `${rangeStart.value} ~ ${rangeEnd.value}`
}

async function loadAnalysis() {
  loading.value = true
  errorMsg.value = ''
  try {
    result.value = await profileAgentApi.analysis(rangeStart.value, rangeEnd.value)
    loadHeatmap()
  } catch (e) {
    errorMsg.value = e.message || '分析请求失败'
    result.value = null
  } finally {
    loading.value = false
  }
}

async function runAnalysis() {
  if (guard('触发画像分析')) return
  running.value = true
  try {
    result.value = await profileAgentApi.run(rangeStart.value, rangeEnd.value)
    notify('分析完成,运行记录已留痕')
  } catch (e) {
    errorMsg.value = e.message || '分析失败(运行记录已留痕,可重试)'
  } finally {
    running.value = false
  }
}

async function submitRisks() {
  if (guard('提交风险建议')) return
  if (!risks.value.length) { notify('当前时间范围内没有团队风险'); return }
  try {
    const ids = await profileAgentApi.submitRiskSuggestions(rangeStart.value, rangeEnd.value)
    notify(`已提交 ${ids.length} 条风险建议至「AI 审核中心」`)
  } catch (e) {
    notify(e.message || '提交失败')
  }
}

async function loadDifficulty() {
  difficultyTab.value = !difficultyTab.value
  if (difficultyTab.value && !difficulties.value.length) {
    try { difficulties.value = await profileAgentApi.difficulty() } catch { /* 空态 */ }
  }
}

async function loadHeatmap() {
  try {
    heatmapRows.value = await profileAgentApi.heatmap(rangeStart.value, rangeEnd.value)
  } catch { /* 空态 */ }
}

async function pickDay(date) {
  selectedDay.value = date
  const row = heatmapRows.value.find(r => r.date === date)
  if (!row || !row.total) {
    selectedDayActs.value = []
    notify('该日无活动记录')
    return
  }
  try {
    selectedDayActs.value = await profileAgentApi.activities({ start: date, end: date })
  } catch {
    selectedDayActs.value = []
  }
}

function toggleCompare() {
  compareOpen.value = !compareOpen.value
  if (compareOpen.value && !prevStart.value) {
    // 默认上期 = 当前周期向前平移相同长度
    const len = Math.max(1, (new Date(rangeEnd.value) - new Date(rangeStart.value)) / 86400000 + 1)
    const pe = new Date(rangeStart.value)
    pe.setDate(pe.getDate() - 1)
    const ps = new Date(pe)
    ps.setDate(ps.getDate() - len + 1)
    prevStart.value = iso(ps)
    prevEnd.value = iso(pe)
  }
}

async function loadCompare() {
  if (!prevStart.value || !prevEnd.value) { notify('请先选择对比周期'); return }
  compareLoading.value = true
  try {
    compareResult.value = await profileAgentApi.compare(prevStart.value, prevEnd.value, rangeStart.value, rangeEnd.value)
  } catch (e) {
    notify(e.message || '对比分析失败')
  } finally {
    compareLoading.value = false
  }
}

const anomalyItems = m => {
  const o = m.objective || {}
  return [
    ...(o.rework_flags || []),
    ...(o.similar_repeated_commits || []),
    ...(o.possibly_late_tasks || []).map(t => `任务 ${t} 计划结束周已过仍未完成(延期风险)`)
  ]
}
const RISK_TEXT = { overload: '🔴 成员负载过高', single_point: '🟠 关键单点依赖', stalled: '🟡 任务长期无进展', missing_review: '🔵 高难度任务缺 Review', milestone_risk: '🟣 里程碑延期风险' }

const LEVEL_TEXT = { low: '低', medium: '中', high: '高', extreme: '极高' }
const LEVEL_COLOR = { low: '#4caf50', medium: '#ffc107', high: '#ff9800', extreme: '#f44336' }
const TYPE_TEXT = { commit: 'Commit', pr: 'PR', review: 'Review', bugfix: '缺陷修复', task_done: '任务完成', note: '状态更新' }

loadAnalysis()
</script>

<template>
  <div class="profile-agent">
    <p class="pool-note">
      画像智能体根据活动事实 / 任务 / 难度 / 负载分析每个人做了什么、承担了多大难度、项目有何风险。
      每条结论带证据;区分<b>客观事实</b>与 <b>AI 推断</b>;不把提交次数等同于工作量或质量。
    </p>

    <!-- 时间范围 + 操作(文档 4.9) -->
    <div class="pa-toolbar">
      <label>开始 <input v-model="rangeStart" type="date"></label>
      <label>结束 <input v-model="rangeEnd" type="date"></label>
      <button class="ghost" :disabled="loading" @click="loadAnalysis">查询</button>
      <span style="flex:1"></span>
      <button class="ghost" :disabled="running" @click="runAnalysis">{{ running ? '分析中…' : '▶ 触发正式分析(留痕)' }}</button>
      <button class="primary" :disabled="!risks.length" @click="submitRisks">⚖ 风险建议送审({{ risks.length }})</button>
    </div>
    <p v-if="errorMsg" class="pool-note" style="color:#e05555">⚠ {{ errorMsg }}</p>

    <div v-if="result">
      <div class="pa-meta small">
        分析范围 {{ fmtRange }} · 数据来源: {{ (result.data_sources || []).join(' / ') }} · 生成于 {{ result.generated_at }}
      </div>

      <!-- 成员工作摘要(4.3) -->
      <div class="h-sec" style="margin-top:10px">成员工作摘要 · {{ result.range_start }} ~ {{ result.range_end }}</div>
      <div class="pa-member-tabs">
        <button v-for="m in members" :key="m.user_id" class="pa-tab"
                :class="{ on: currentMember && currentMember.user_id === m.user_id }"
                @click="activeMember = m.user_id">
          {{ m.display_name }}<span class="small"> 负载 {{ m.current_load_percent }}%</span>
        </button>
      </div>

      <div v-if="currentMember" class="sum-grid">
        <div class="sum-card">
          <h4>🧾 客观事实(来自活动/任务数据)</h4>
          <ul>
            <li v-for="(v, k) in currentMember.objective.period_activity_counts" :key="k" v-show="v > 0">
              {{ TYPE_TEXT[k] || k }}: {{ v }} 次
            </li>
            <li>承担任务 {{ currentMember.objective.tasks_owned.length }} 项,完成 {{ currentMember.objective.tasks_done }} 项,
              高难度 {{ currentMember.objective.high_difficulty_tasks }} 项,分配工时 {{ currentMember.objective.assigned_hours }}h</li>
          </ul>
          <div v-if="currentMember.objective.work_items.length" class="pa-items">
            <div v-for="(w, i) in currentMember.objective.work_items.slice(0, 12)" :key="i" class="pa-item">{{ w }}</div>
          </div>
          <p v-else class="small">该时间范围内暂无活动记录数据,需要成员补充说明。</p>
          <p v-if="currentMember.objective.unlinked_activities.length" class="small" style="color:#b8860b">
            ⚠ {{ currentMember.objective.unlinked_activities.length }} 条活动未关联任务(可能属于调试/返工/拆分问题)
          </p>
        </div>
        <div class="sum-card">
          <h4>◈ 动态能力画像(AI 推断,持续更新)</h4>
          <ul>
            <li>擅长方向: {{ currentMember.dynamic_profile.good_at }}</li>
            <li>难度承受: {{ currentMember.dynamic_profile.difficulty_capacity }}</li>
            <li>交付及时性: {{ currentMember.dynamic_profile.delivery_timeliness }}</li>
            <li>当前负载: {{ currentMember.dynamic_profile.current_load_percent }}%({{ currentMember.dynamic_profile.risk_flags }})</li>
            <li>推荐任务类型: {{ currentMember.dynamic_profile.recommended_task_types.join(';') }}</li>
          </ul>
          <h4 style="margin-top:10px">◆ AI 推断(带依据)</h4>
          <ul>
            <li v-for="(inf, i) in currentMember.ai_inference" :key="i">{{ inf }}</li>
          </ul>
        </div>
      </div>

      <!-- 贡献绿格子热力图(4.10):活动分布≠工作质量 -->
      <div class="h-sec" style="margin-top:12px">贡献活动热力图(绿格子 = 活动分布,不代表工作质量)</div>
      <div class="pa-heatmap">
        <div v-for="row in heatmapRows" :key="row.date" class="pa-cell"
             :class="{ sel: selectedDay === row.date }"
             :style="{ background: HEAT_COLORS[heatLevel(row.total)] }"
             :title="row.date + ' 共 ' + row.total + ' 条活动'" @click="pickDay(row.date)">
        </div>
      </div>
      <p class="small" style="margin:4px 0 0">
        颜色深浅 = 当日活动数量(Commit/PR/Review/缺陷修复/任务完成/状态更新);点击某天查看当天具体活动。
        绿格子展示的是<b>活动分布</b>,不等于工作质量。
      </p>
      <div v-if="selectedDay && selectedDayActs.length" class="sum-card pa-day-acts">
        <h4>{{ selectedDay }} 的具体活动({{ selectedDayActs.length }} 条)</h4>
        <div v-for="a in selectedDayActs" :key="a.activity_id" class="pa-item">
          [{{ TYPE_TEXT[a.activity_type] || a.activity_type }}] {{ a.title }}
          <span v-if="a.module" class="small">({{ a.module }})</span>
        </div>
      </div>

      <!-- 任务难度(4.5) -->
      <div class="h-sec" style="margin-top:12px">
        <button class="ghost" @click="loadDifficulty">{{ difficultyTab ? '收起' : '展开' }}任务难度分析</button>
      </div>
      <div v-if="difficultyTab" class="pa-diff">
        <div v-for="d in difficulties" :key="d.task_id" class="pa-diff-row">
          <span class="tag2" :style="{ background: LEVEL_COLOR[d.level] }">{{ LEVEL_TEXT[d.level] }}难度 {{ d.score }}</span>
          <b>{{ d.task_id }}</b>
          <span class="small">{{ (d.basis || []).join(';') }}</span>
        </div>
      </div>

      <!-- 提交异常模式(4.6) -->
      <div class="h-sec" style="margin-top:12px">提交异常模式(证据化描述,不作个人评判)</div>
      <div v-if="currentMember && anomalyItems(currentMember).length" class="sum-card pa-anomaly">
        <ul>
          <li v-for="(a, i) in anomalyItems(currentMember)" :key="i">⚠ {{ a }}</li>
          <li v-if="(currentMember.objective.unclear_commit_messages || []).length" class="small" style="margin-top:6px">
            另有 {{ currentMember.objective.unclear_commit_messages.length }} 条提交信息不清晰:{{ currentMember.objective.unclear_commit_messages.slice(0, 3).join(';') }}
          </li>
          <li v-if="(currentMember.objective.done_tasks_without_activity || []).length" class="small">
            任务已标记完成但无代码活动:{{ currentMember.objective.done_tasks_without_activity.join(';') }}
          </li>
        </ul>
      </div>
      <p v-else-if="currentMember" class="pool-note">该成员在当前范围未发现提交异常模式。</p>

      <!-- 团队风险(4.8) -->
      <div class="h-sec" style="margin-top:12px">团队风险与协调建议</div>
      <div v-if="risks.length" class="pa-risks">
        <div v-for="(r, i) in risks" :key="i" class="sum-card pa-risk">
          <h4>
            {{ RISK_TEXT[r.kind] || '⚪ 项目风险' }}
            — {{ r.member || r.task_name }}
          </h4>
          <p class="small">证据: {{ r.evidence }}</p>
          <p class="small">推断: {{ r.inference }}</p>
          <p class="small" style="color:#2e7d32">建议行动: {{ r.suggested_action }}</p>
        </div>
      </div>
      <p v-else class="pool-note">当前时间范围内未发现团队级风险。</p>

      <!-- 时间范围对比(4.9):本期 vs 上期 -->
      <div class="h-sec" style="margin-top:12px">
        <button class="ghost" @click="toggleCompare">{{ compareOpen ? '收起' : '展开' }}时间范围对比分析(本期 vs 上期)</button>
      </div>
      <div v-if="compareOpen" class="pa-compare">
        <div class="pa-toolbar">
          <label>上期开始 <input v-model="prevStart" type="date"></label>
          <label>上期结束 <input v-model="prevEnd" type="date"></label>
          <button class="ghost" :disabled="compareLoading" @click="loadCompare">{{ compareLoading ? '对比中…' : '对比' }}</button>
          <span class="small">当前期: {{ rangeStart }} ~ {{ rangeEnd }}</span>
        </div>
        <div v-if="compareResult" class="pa-compare-body">
          <div class="small pa-meta">
            上期 {{ compareResult.previous_range }} ↔ 本期 {{ compareResult.current_range }} · 生成于 {{ compareResult.generated_at }}
          </div>
          <div v-for="c in compareResult.comparisons" :key="c.user_id" class="pa-compare-row">
            <b>{{ c.display_name }}</b>
            <ul>
              <li v-for="(ch, i) in c.changes" :key="i">{{ ch }}</li>
            </ul>
            <p class="small" style="opacity:.8">
              上期: 活动 {{ c.previous_period.total_activities }} · Review {{ c.previous_period.review_count }} ·
              完成任务 {{ c.previous_period.task_done_count }} · 完成率 {{ c.previous_period.completion_rate_percent }}% ·
              预计工时 {{ c.previous_period.assigned_hours }}h
            </p>
            <p class="small" style="opacity:.8">
              本期: 活动 {{ c.current_period.total_activities }} · Review {{ c.current_period.review_count }} ·
              完成任务 {{ c.current_period.task_done_count }} · 完成率 {{ c.current_period.completion_rate_percent }}% ·
              预计工时 {{ c.current_period.assigned_hours }}h
            </p>
            <p class="small" style="color:#888">{{ c.current_period.actual_hours }}</p>
          </div>
        </div>
      </div>
    </div>
    <p v-else-if="loading" class="pool-note">分析中…</p>
  </div>
</template>

<style scoped>
.pa-toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 8px 0; }
.pa-toolbar label { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.pa-meta { opacity: .75; margin: 4px 0 8px; }
.pa-member-tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.pa-tab { border: 2px solid var(--ink, #333); background: transparent; padding: 6px 12px; cursor: pointer; font-family: inherit; }
.pa-tab.on { background: var(--ink, #333); color: #fff; }
.pa-items { max-height: 180px; overflow: auto; margin: 8px 0; }
.pa-item { font-size: 12px; padding: 3px 0; border-bottom: 1px dashed rgba(0,0,0,.12); }
.pa-diff-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px dashed rgba(0,0,0,.12); }
.pa-risks { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px; }
.pa-heatmap { display: grid; grid-template-columns: repeat(14, minmax(10px, 1fr)); gap: 3px; margin: 6px 0 2px; }
.pa-cell { aspect-ratio: 1; border-radius: 2px; cursor: pointer; transition: transform .12s; }
.pa-cell:hover { transform: scale(1.25); outline: 1px solid #333; }
.pa-cell.sel { outline: 2px solid #1b5e20; }
.pa-day-acts { margin-top: 8px; max-height: 200px; overflow: auto; }
.pa-anomaly { margin-bottom: 8px; }
.pa-compare-body { display: grid; gap: 10px; margin-top: 8px; }
.pa-compare-row { border: 1px dashed rgba(0,0,0,.25); padding: 8px 10px; }
</style>
