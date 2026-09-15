<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { AGENT_UPDATED_EVENT } from '@/composables/useAgentRefresh'
import { profileAgentApi } from '@/api/profileAgent'

/* 任务提交智能体(Commit Agent):真实数据
   - 最近提交/活动:读取 activity_records(含 GitHub 同步落库的真实事件)
   - GitHub 同步:状态 + 手动触发(与画像智能体面板共用同一后端服务)
   - 成员工作状态(事实):从真实活动按成员聚合,不编造
   - 推断与风险:来自画像分析(AI 推断,带证据)
   - 生成协调建议 → 送审:真实调用后端,进入「AI 审核中心」走人工审核 */
const session = useSessionStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()

const online = computed(() => session.apiMode && !!session.currentUser)
/* 手动同步入口:后端 /github/sync 与其余写接口同为 Roles.writer()(admin/owner/member),
   只有只读查看者(viewer)不可用——成员靠它在自己完成任务后重试同步 */
const canWrite = computed(() => !session.isViewer)

/* 时间范围:默认近 30 天 */
function iso(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = new Date()
const rangeStart = ref(iso(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 29)))
const rangeEnd = ref(iso(today))

/* ---------- GitHub 同步(状态 + 手动触发) ---------- */
const githubStatus = ref(null)
const githubSyncing = ref(false)
async function checkGithub() {
  try {
    githubStatus.value = await profileAgentApi.githubStatus()
  } catch (e) {
    githubStatus.value = { enabled: false, error: e.message }
  }
}
async function syncGithub() {
  if (guard('触发 GitHub 同步')) return
  githubSyncing.value = true
  try {
    const r = await profileAgentApi.githubSync(rangeStart.value, rangeEnd.value)
    // 未配置:后端返回单数 error,原样转述
    if (r.error) { notify(r.error); return }
    // 拉取失败(403 限流/超时/404/非 JSON)后端写进 errors[],不是 error:
    // 必须如实报失败,不能因为 pulled/synced/skipped 缺失就用 ?? 0 显示成"同步完成 0 条"
    const errs = Array.isArray(r.errors) ? r.errors : []
    if (errs.length) notify(`GitHub 同步未完成:${errs.length} 项事件拉取失败 — ${errs[0]}`)
    else notify(`GitHub 同步完成:拉取 ${(r.pulled?.commits ?? 0) + (r.pulled?.prs ?? 0) + (r.pulled?.reviews ?? 0) + (r.pulled?.issues ?? 0)} 条事件,入库 ${r.synced ?? 0},跳过 ${r.skipped ?? 0}`)
    if (r.warnings && r.warnings.length) notify(`有 ${r.warnings.length} 条事件无法归属成员(已跳过,见警告)`)
    lastSync.value = r
    await Promise.all([checkGithub(), loadActivities()])
  } catch (e) {
    notify(e.message || 'GitHub 同步失败')
  } finally {
    githubSyncing.value = false
  }
}
const lastSync = ref(null)

/* ---------- 真实活动列表 ---------- */
const activities = ref([])
const activitiesLoading = ref(false)
const activitiesError = ref('')
const TYPE_TEXT = { commit: 'Commit', pr: 'PR', review: 'Review', bugfix: '缺陷修复', task_done: '任务完成', note: '状态更新', test: '测试' }

async function loadActivities() {
  if (!online.value) return
  activitiesLoading.value = true
  activitiesError.value = ''
  try {
    const rows = await profileAgentApi.activities({ start: rangeStart.value, end: rangeEnd.value })
    rows.sort((a, b) => (b.happened_at || '').localeCompare(a.happened_at || ''))
    activities.value = rows
  } catch (e) {
    activitiesError.value = e.message || '加载活动失败'
    activities.value = []
  } finally {
    activitiesLoading.value = false
  }
}

/* 活动列表:按成员可查 + 如实报总条数。
   真实数据是 67 条活动,而列表原先直接 slice(0,20):排在后面的成员(罗子涵 2 条、高思晗 1 条,
   提交日期更早)在界面上一条都看不到,而且界面不说自己被截断了 —— 既看不到也不知道。
   现在:成员筛选项 + 「共 N 条,当前显示最近 20 条」+ 可展开全部。 */
const ACT_LIMIT = 20
const activeUid = ref(null)      // null = 全部
const showAll = ref(false)
const filteredActivities = computed(() => (activeUid.value == null
  ? activities.value
  : activities.value.filter(a => a.user_id === activeUid.value)))
const shownActivities = computed(() => (showAll.value
  ? filteredActivities.value
  : filteredActivities.value.slice(0, ACT_LIMIT)))
const listTruncated = computed(() => !showAll.value && filteredActivities.value.length > ACT_LIMIT)
function pickMember(uid) {
  activeUid.value = activeUid.value === uid ? null : uid
  showAll.value = false
}

/* ---------- 成员工作状态(事实,按真实活动聚合) + 推断与风险(画像分析) ---------- */
const analysis = ref(null)
const analysisLoading = ref(false)
const analysisError = ref('')

async function loadAnalysis() {
  if (!online.value) return
  analysisLoading.value = true
  analysisError.value = ''
  try {
    analysis.value = await profileAgentApi.analysis(rangeStart.value, rangeEnd.value)
  } catch (e) {
    analysisError.value = e.message || '分析加载失败'
    analysis.value = null
  } finally {
    analysisLoading.value = false
  }
}

const nameOf = computed(() => {
  const map = {}
  ;(analysis.value?.members || []).forEach(m => { map[m.user_id] = m.display_name })
  return map
})

const memberFacts = computed(() => {
  const by = {}
  for (const a of activities.value) {
    const uid = a.user_id
    if (!by[uid]) by[uid] = { uid, counts: {}, lastAt: '' }
    const c = by[uid]
    c.counts[a.activity_type] = (c.counts[a.activity_type] || 0) + 1
    if (!c.lastAt || a.happened_at > c.lastAt) c.lastAt = a.happened_at
  }
  return Object.values(by)
    .map(f => {
      const total = Object.values(f.counts).reduce((a, b) => a + b, 0)
      const lastDay = f.lastAt ? f.lastAt.slice(0, 10) : ''
      const daysAgo = lastDay ? Math.floor((new Date(rangeEnd.value) - new Date(lastDay)) / 86400000) : null
      const dormant = daysAgo !== null && daysAgo >= 3
      return {
        uid: f.uid,
        name: nameOf.value[f.uid] || `成员#${f.uid}`,
        counts: f.counts,
        total,
        lastAt: f.lastAt,
        dormant,
        countsText: Object.entries(f.counts).map(([t, n]) => `${TYPE_TEXT[t] || t}×${n}`).join(' ')
      }
    })
    .sort((a, b) => b.total - a.total)
})

const risks = computed(() => (analysis.value?.team_risks || []))
/* kind → 展示文案:必须覆盖后端 team_risks 实际产出的全部 kind,少一个键会静默退化成「⚪ 项目风险」 */
const RISK_TEXT = { overload: '🔴 成员负载过高', single_point: '🟠 关键单点依赖', stalled: '🟡 任务长期无进展', missing_review: '🔵 高难度任务缺 Review', missing_test: '🟤 任务缺测试活动记录', module_concentration: '⚫ 模块工作过于集中', milestone_risk: '🟣 里程碑延期风险' }

/* ---------- 生成协调建议 → 送审(真实闭环) ---------- */
const submitting = ref(false)
async function submitSuggestions() {
  if (guard('生成协调建议')) return
  if (!risks.value.length) { notify('当前时间范围内没有可送审的团队风险'); return }
  submitting.value = true
  try {
    const ids = await profileAgentApi.submitRiskSuggestions(rangeStart.value, rangeEnd.value, '任务提交智能体')
    notify(`已生成 ${ids.length} 条协调建议并送审,前往「AI 审核中心」处理`)
  } catch (e) {
    notify(e.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

function fmtSync(p) {
  if (!p) return ''
  const pd = p.pulled || {}
  const n = (pd.commits ?? 0) + (pd.prs ?? 0) + (pd.reviews ?? 0) + (pd.issues ?? 0)
  return `拉取 ${n} 条 → 入库 ${p.synced ?? 0} / 跳过 ${p.skipped ?? 0}${p.warnings?.length ? ` / ${p.warnings.length} 条警告` : ''}`
}

/* 登录态就绪后再拉取(与 AiView 对会议 store 的 watch 同范式):
   - 原实现在 setup 顶层无条件 checkGithub(),离线演示模式 / 未登录时必然 401,
     client.js 的 401 分支会触发全局 handleUnauthorized() → 误弹「登录已过期」并强制打开登录框,
     且 githubStatus 被永久污染成 { enabled:false, error:'未登录' };
   - 原 onMounted 只在挂载那一刻判断一次 online:若先挂载后登录,活动与分析永远不会自动加载 */
watch(() => online.value, v => {
  if (!v) return
  checkGithub()
  loadActivities()
  loadAnalysis()
}, { immediate: true })

/* 成员把任务标记完成 → useAgentRefresh 触发智能体读仓库并评估 → 广播本事件。
   面板已挂载时立即重新拉取(活动列表 = 智能体找到的提交物),不用刷新整页 */
function onAgentUpdated() {
  if (!online.value) return
  checkGithub()
  loadActivities()
  loadAnalysis()
}
onMounted(() => window.addEventListener(AGENT_UPDATED_EVENT, onAgentUpdated))
onBeforeUnmount(() => window.removeEventListener(AGENT_UPDATED_EVENT, onAgentUpdated))
</script>

<template>
  <div class="submit-agent">
    <p class="pool-note">
      任务提交智能体读取<b>真实活动记录</b>(activity_records,含 GitHub 同步的 commit / PR / Review / Issue 事件)。
      结论区分<b>客观事实</b>与 <b>AI 推断</b>;不把提交次数等同于工作量或质量,也不作为人员奖惩依据。
    </p>

    <!-- GitHub 同步区(状态 + 手动触发,与画像智能体共用后端服务) -->
    <div class="sa-sync" :class="{ on: githubStatus?.enabled }">
      <div class="sa-sync-info">
        <span class="sa-sync-dot" :class="githubStatus?.enabled ? 'ok' : 'off'"></span>
        <b>GitHub 数据同步</b>
        <span class="small">
          <template v-if="githubStatus?.enabled">
            已启用 · 仓库 {{ githubStatus.configured_repo }}<span v-if="githubStatus.auto_sync_hours > 0"> · 每 {{ githubStatus.auto_sync_hours }}h 自动同步</span>
          </template>
          <template v-else>{{ githubStatus?.error || '未配置' }}</template>
        </span>
      </div>
      <div class="sa-sync-ops">
        <span v-if="lastSync" class="small mono">{{ fmtSync(lastSync) }}</span>
        <button v-if="canWrite" id="submit-github-sync" class="ghost" :disabled="githubSyncing" @click="syncGithub">
          {{ githubSyncing ? '同步中…' : '⟳ 立即同步 GitHub' }}
        </button>
        <span v-else class="small" style="opacity:.7">同步需 管理员/负责人 权限</span>
      </div>
      <span v-if="lastSync && lastSync.errors && lastSync.errors.length" class="small" role="alert"
            style="flex:1 0 100%;color:#e05555">
        ⚠ 同步未完成:{{ lastSync.errors.length }} 项失败 — {{ lastSync.errors.join('；') }}
      </span>
    </div>

    <!-- 最近提交/活动(真实) -->
    <div class="h-sec" style="margin-top:0">
      最近活动 · {{ rangeStart }} ~ {{ rangeEnd }}
      <span class="small" style="font-weight:400">
        · 共 {{ filteredActivities.length }} 条<template v-if="listTruncated">，当前显示最近 {{ ACT_LIMIT }} 条</template>
      </span>
    </div>
    <p v-if="!online" class="small">离线演示模式:需连接后端读取真实活动(在线登录后自动加载)。</p>
    <p v-else-if="activitiesLoading" class="small">正在加载真实活动…</p>
    <p v-else-if="activitiesError" class="small" style="color:#e05555" role="alert">加载失败:{{ activitiesError }}</p>
    <template v-else-if="activities.length">
      <!-- 按成员筛选:每个人的提交物都要查得到,不能只靠"最近 20 条" -->
      <div class="sa-filter" id="act-filter">
        <button type="button" data-act-member="all" :class="{ active: activeUid === null }" @click="pickMember(null)">
          全部 {{ activities.length }}
        </button>
        <button
          v-for="f in memberFacts" :key="f.uid" type="button"
          :data-act-member="f.uid" :class="{ active: activeUid === f.uid }"
          @click="pickMember(f.uid)"
        >{{ f.name }} {{ f.total }}</button>
      </div>
      <div id="commits" style="margin-bottom:14px">
        <div v-for="a in shownActivities" :key="a.activity_id" class="commit-row">
          <span class="tag2">{{ TYPE_TEXT[a.activity_type] || a.activity_type }}</span>
          <span class="msg">{{ a.title }}</span>
          <span v-if="a.module" class="small" style="flex:0 0 auto">{{ a.module }}</span>
          <span class="small" style="flex:0 0 auto">{{ nameOf[a.user_id] || `成员#${a.user_id}` }}</span>
          <span class="sha">{{ a.happened_at ? a.happened_at.slice(0, 10) : '' }}</span>
        </div>
        <p v-if="!filteredActivities.length" class="pool-note">
          该成员在此时间范围内没有活动记录。
          <button type="button" class="ghost" data-act-member="all" @click="pickMember(null)">返回全部</button>
        </p>
        <button v-else-if="listTruncated" type="button" id="act-show-all" class="ghost" @click="showAll = true">
          展开全部 {{ filteredActivities.length }} 条
        </button>
        <button v-else-if="showAll && filteredActivities.length > ACT_LIMIT" type="button" id="act-show-less" class="ghost" @click="showAll = false">
          只看最近 {{ ACT_LIMIT }} 条
        </button>
      </div>
    </template>
    <p v-else class="pool-note">该时间范围内暂无活动记录。可先在上方触发 GitHub 同步,或在「画像智能体」面板录入活动事实。</p>

    <!-- 成员工作状态(事实,聚合自真实活动) -->
    <div class="h-sec">成员工作状态(事实)</div>
    <p v-if="!memberFacts.length" class="pool-note">暂无成员活动数据。</p>
    <div v-else class="sum-grid">
      <div v-for="f in memberFacts" :key="f.uid" class="sum-card">
        <h4>{{ f.name }} <span class="small">(共 {{ f.total }} 条活动)</span></h4>
        <ul>
          <li>{{ f.countsText || '—' }}</li>
          <li>最近活动: {{ f.lastAt || '—' }}</li>
          <li v-if="f.dormant" class="warn">⚠ 近 3 天无活动(可能停滞,需确认)</li>
        </ul>
      </div>
    </div>

    <!-- 推断与风险(AI 推断,带证据) -->
    <div class="h-sec">推断与风险(AI)</div>
    <p v-if="!online" class="small">离线演示模式:需连接后端读取分析。</p>
    <p v-else-if="analysisLoading" class="small">分析中…</p>
    <p v-else-if="analysisError" class="small" style="color:#e05555" role="alert">分析加载失败:{{ analysisError }}</p>
    <div v-else-if="risks.length" class="sum-grid">
      <div v-for="(r, i) in risks" :key="i" class="sum-card">
        <h4>{{ RISK_TEXT[r.kind] || '⚪ 项目风险' }} — {{ r.member || r.task_name }}</h4>
        <p class="small">证据: {{ r.evidence }}</p>
        <p class="small">推断: {{ r.inference }}</p>
        <p class="small" style="color:#2e7d32">建议: {{ r.suggested_action }}</p>
      </div>
    </div>
    <p v-else class="pool-note">当前时间范围内未发现团队级风险。</p>

    <!-- 生成协调建议 → 送审(真实闭环) -->
    <div style="display:flex;align-items:center;gap:12px;margin-top:14px;flex-wrap:wrap">
      <button class="primary" id="submit-gen" :disabled="submitting" @click="submitSuggestions">
        {{ submitting ? '提交中…' : '⚖ 生成协调建议 → 送审(进入 AI 审核中心)' }}
      </button>
      <span class="small" style="opacity:.75">
        送审前将重新计算当前时间范围的团队风险,建议送审后前往「AI 审核中心」人工确认
      </span>
    </div>
  </div>
</template>

<style scoped>
.sa-sync { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; border: 1px dashed rgba(0,0,0,.3); border-radius: 10px; padding: 10px 12px; margin-bottom: 16px; }
.sa-sync.on { border-color: rgba(46,125,50,.5); background: rgba(46,125,50,.04); }
.sa-sync-info { display: flex; align-items: center; gap: 8px; }
.sa-sync-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.sa-sync-dot.ok { background: #2e7d32; }
.sa-sync-dot.off { background: #b8860b; }
.sa-sync-ops { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-wrap: wrap; }
.sum-card h4 .warn { color: #b8860b; }
.commit-row .msg { flex: 1; }
.sa-filter { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sa-filter button { padding: 3px 9px; font-size: 11px; font-family: var(--mono); font-weight: 700; }
.sa-filter button.active { background: var(--green); }
</style>
