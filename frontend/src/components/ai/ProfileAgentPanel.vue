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

const members = computed(() => (result.value?.members || []))
const risks = computed(() => (result.value?.team_risks || []))
const currentMember = computed(() =>
  members.value.find(m => m.user_id === activeMember.value) || members.value[0] || null)

function fmtRange() {
  return `${rangeStart.value} ~ ${rangeEnd.value}`
}

async function loadAnalysis() {
  loading.value = true
  errorMsg.value = ''
  try {
    result.value = await profileAgentApi.analysis(rangeStart.value, rangeEnd.value)
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

      <!-- 团队风险(4.8) -->
      <div class="h-sec" style="margin-top:12px">团队风险与协调建议</div>
      <div v-if="risks.length" class="pa-risks">
        <div v-for="(r, i) in risks" :key="i" class="sum-card pa-risk">
          <h4>
            {{ r.kind === 'overload' ? '🔴 成员负载过高' : r.kind === 'single_point' ? '🟠 关键单点依赖' : '🟡 任务长期无进展' }}
            — {{ r.member || r.task_name }}
          </h4>
          <p class="small">证据: {{ r.evidence }}</p>
          <p class="small">推断: {{ r.inference }}</p>
          <p class="small" style="color:#2e7d32">建议行动: {{ r.suggested_action }}</p>
        </div>
      </div>
      <p v-else class="pool-note">当前时间范围内未发现团队级风险。</p>
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
</style>
