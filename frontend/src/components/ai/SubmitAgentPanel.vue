<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useProjectsStore } from '@/stores/projects'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { AGENT_UPDATED_EVENT } from '@/composables/useAgentRefresh'
import { profileAgentApi } from '@/api/profileAgent'

/* 任务提交智能体(Commit Agent):真实数据
   - 最近提交/活动:读取 activity_records(含 GitHub 同步落库的真实事件)
   - GitHub 同步:状态 + 手动触发(与画像智能体面板共用同一后端服务)
   - 成员工作状态(事实):从真实活动按成员聚合,不编造
   - 推断与风险:来自画像分析(AI 推断,带证据)
   - 生成协调建议 → 送审:真实调用后端,进入「提案审核中心」走人工审核 */
const session = useSessionStore()
const projects = useProjectsStore()
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
/* 配置齐全 ≠ 可用:后端 status() 会带 token.state(ok/invalid/no_access/forbidden/unreachable)。
   此前 token 过期时页面照样显示"已启用",用户要等同步失败才发现。 */
const tokenBroken = computed(() => !!githubStatus.value?.enabled
  && !!githubStatus.value?.token && githubStatus.value.token.state !== 'ok')
const syncDot = computed(() => {
  if (!githubStatus.value?.enabled) return 'off'
  return tokenBroken.value ? 'warn' : 'ok'
})
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
    const rows = await profileAgentApi.activities({ start: rangeStart.value, end: rangeEnd.value }, projects.currentProjectId)
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
    analysis.value = await profileAgentApi.analysis(rangeStart.value, rangeEnd.value, projects.currentProjectId)
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

/* 结论来源:后端已拆成 members_engine / risks_engine。
   engine 只表示"任一环节走了 LLM",拿它标风险区会误导 —— 曾出现 engine=llm 但风险全是规则引擎的情况。
   这里优先读 risks_engine(老接口回退 engine)。 */
const risksEngine = computed(() => analysis.value?.risks_engine || analysis.value?.engine || '')
const isLlmRisks = computed(() => risksEngine.value === 'llm')
const engineLabel = computed(() => (isLlmRisks.value ? 'LLM 分析' : '规则引擎(预览)'))

/* kind → 展示元信息(文案/严重度/提示)。
   severity 说明:
   - high  : 直接冲击交付(过载/单点/已延期)
   - medium: 需人工确认的信号(停滞/模块集中)
   - low   : 多为"活动数据缺失"触发的提示,补录数据后通常自行消失 */
const RISK_META = {
  overload: { text: '成员负载过高', icon: '🔴', severity: 'high', hint: '' },
  single_point: { text: '关键单点依赖', icon: '🟠', severity: 'high', hint: '' },
  milestone_risk: {
    text: '里程碑延期风险', icon: '🟣', severity: 'high',
    hint: '项目周由「最早活动记录」推算;该锚点不准时,这里的周次与延期判定会整体偏移'
  },
  stalled: {
    text: '任务长期无进展', icon: '🟡', severity: 'medium',
    hint: '需与负责人确认:是真阻塞、任务拆分不清,还是活动没录入'
  },
  module_concentration: { text: '模块工作过于集中', icon: '⚫', severity: 'medium', hint: '' },
  missing_review: {
    text: '高难度任务缺 Review', icon: '🔵', severity: 'low',
    hint: '「缺 Review」可能只是活动没录/没同步;已评审请先补录,该提示会随之消失'
  },
  missing_test: {
    text: '任务缺测试活动记录', icon: '🟤', severity: 'low',
    hint: '同上:测试做了但活动未录入时同样会命中'
  }
}
/* 预设清单之外的 kind:LLM 允许"基于证据提出其他类型",前端必须接得住。
   此前兜底是 severity:'low',会被分到「低」档并默认折叠 —— 模型提出的新洞察等于被埋掉。
   现在:标为 medium(不预设它不重要)+ 默认展开 + 显式标注「模型新增」,交由人工研判。 */
const FALLBACK_META = {
  text: '模型新增风险', icon: '✨', severity: 'medium', custom: true,
  hint: '该类型不在预设风险清单内,由模型基于证据自行提出;严重度未预设,请结合证据人工研判'
}
const SEVERITY_TEXT = { high: '高', medium: '中', low: '低' }
const SEVERITY_ORDER = { high: 0, medium: 1, low: 2 }
/** 未知 kind 把类型名带进标题,避免所有自定义类型都挤成同一组「项目风险」 */
const metaOf = k => RISK_META[k]
  || { ...FALLBACK_META, text: FALLBACK_META.text + ' · ' + (k || '未命名') }

/* 规则引擎是「逐任务 × 逐模式」枚举,条数天然膨胀 → 前端做分组/分级/折叠,让它可读 */
const riskView = ref('kind')        // 'kind' 按类型 | 'member' 按成员
const riskSeverity = ref('')        // '' 全部 | high | medium | low
const groupToggled = ref({})        // 用户显式折叠过的组(未记录时走默认)
const overflowOpen = ref({})        // 组内「展开剩余 N 条」
const ITEM_LIMIT = 6                // 单个分组默认最多显示条数

const visibleRisks = computed(() =>
  riskSeverity.value ? risks.value.filter(r => metaOf(r.kind).severity === riskSeverity.value) : risks.value
)
const severityCounts = computed(() => {
  const c = { high: 0, medium: 0, low: 0 }
  for (const r of risks.value) c[metaOf(r.kind).severity]++
  return c
})
const riskGroups = computed(() => {
  const byMember = riskView.value === 'member'
  const map = new Map()
  for (const r of visibleRisks.value) {
    const meta = metaOf(r.kind)
    const key = byMember ? (r.member || '未分配') : r.kind
    if (!map.has(key)) {
      map.set(key, {
        key,
        title: byMember ? key : (meta.icon + ' ' + meta.text),
        severity: meta.severity,
        hint: byMember ? '' : meta.hint,
        custom: !!meta.custom,
        items: [],
        counts: { high: 0, medium: 0, low: 0 }
      })
    }
    const g = map.get(key)
    g.items.push(r)
    g.counts[meta.severity]++
    if (meta.custom) g.custom = true
  }
  const groups = [...map.values()]
  return byMember
    ? groups.sort((a, b) => b.items.length - a.items.length)
    : groups.sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]
        || (a.custom === b.custom ? 0 : a.custom ? -1 : 1)   // 同档内:模型新增类型靠前
        || b.items.length - a.items.length)
})
/** 默认展开:高严重度展开,中/低折叠;模型新增类型一律展开(否则新洞察看不见) */
function isGroupOpen(g) {
  const v = groupToggled.value[g.key]
  return v === undefined ? (g.severity === 'high' || !!g.custom) : v
}
function toggleGroup(g) {
  groupToggled.value = { ...groupToggled.value, [g.key]: !isGroupOpen(g) }
}
function groupItems(g) {
  return overflowOpen.value[g.key] ? g.items : g.items.slice(0, ITEM_LIMIT)
}
function toggleOverflow(g) {
  overflowOpen.value = { ...overflowOpen.value, [g.key]: !overflowOpen.value[g.key] }
}
function setAllGroups(open) {
  const next = {}
  for (const g of riskGroups.value) next[g.key] = open
  groupToggled.value = next
}

/* 手动触发一次带 LLM 的正式分析(预览恒为规则引擎,不烧额度) */
const runningLlm = ref(false)
async function runLlmAnalysis() {
  if (guard('运行 LLM 分析')) return
  runningLlm.value = true
  analysisError.value = ''
  try {
    analysis.value = await profileAgentApi.run(
      rangeStart.value, rangeEnd.value, null, projects.currentProjectId)
  } catch (e) {
    analysisError.value = e.message || 'LLM 分析失败'
  } finally {
    runningLlm.value = false
  }
}

/* ---------- 生成协调建议 → 送审(真实闭环) ---------- */
const submitting = ref(false)
async function submitSuggestions() {
  if (guard('生成协调建议')) return
  if (!risks.value.length) { notify('当前时间范围内没有可送审的团队风险'); return }
  submitting.value = true
  try {
    const ids = await profileAgentApi.submitRiskSuggestions(rangeStart.value, rangeEnd.value, '任务提交智能体', projects.currentProjectId)
    notify(`已生成 ${ids.length} 条协调建议并送审,前往「提案审核中心」处理`)
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

watch(() => projects.currentProjectId, (next, previous) => {
  if (next === previous) return
  activities.value = []
  analysis.value = null
  activitiesError.value = ''
  analysisError.value = ''
  activeUid.value = null
  showAll.value = false
  lastSync.value = null
  if (next && online.value) {
    checkGithub()
    loadActivities()
    loadAnalysis()
  }
})

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
        <span class="sa-sync-dot" :class="syncDot"></span>
        <b>GitHub 数据同步</b>
        <span class="small">
          <template v-if="githubStatus?.enabled">
            已启用 · 仓库 {{ githubStatus.configured_repo }}<span v-if="githubStatus.auto_sync_hours > 0"> · 每 {{ githubStatus.auto_sync_hours }}h 自动同步</span>
            <!-- 配置齐全不等于能用:token 过期时后端返回 token.state=invalid,这里必须显式告警 -->
            <span v-if="tokenBroken" style="color:#e05555"> · ⚠ {{ githubStatus.token.message }}</span>
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

    <!-- 推断与风险:按实际引擎标注来源,分组/分级/折叠 -->
    <div class="h-sec">
      推断与风险<span v-if="engineLabel"> · {{ engineLabel }}</span>
    </div>
    <p v-if="!online" class="small">离线演示模式:需连接后端读取分析。</p>
    <p v-else-if="analysisLoading" class="small">分析中…</p>
    <p v-else-if="analysisError" class="small" style="color:#e05555" role="alert">分析加载失败:{{ analysisError }}</p>
    <template v-else>
      <p v-if="risksEngine && !isLlmRisks" class="pool-note">
        当前是<b>规则引擎预览</b>:按工时/依赖/活动量等规则逐条枚举,<b>未调用大模型</b>、不落画像快照;
        它不做聚合与优先级判断,条数偏多属正常。需要模型结论请点下方「运行 LLM 分析」(消耗额度、落快照并留痕)。
      </p>

      <template v-if="risks.length">
        <!-- 概览条:严重度分布(可点选筛选)+ 分组维度 + 全展开/折叠 -->
        <div class="risk-bar">
          <div class="risk-chips">
            <button type="button" class="chip" :class="{ on: riskSeverity === '' }" @click="riskSeverity = ''">
              全部 {{ risks.length }}
            </button>
            <button v-for="s in ['high', 'medium', 'low']" :key="s" type="button" class="chip"
                    :class="['sev-' + s, { on: riskSeverity === s }]"
                    @click="riskSeverity = riskSeverity === s ? '' : s">
              {{ SEVERITY_TEXT[s] }} {{ severityCounts[s] }}
            </button>
          </div>
          <div class="risk-views">
            <button type="button" class="chip" :class="{ on: riskView === 'kind' }" @click="riskView = 'kind'">按类型</button>
            <button type="button" class="chip" :class="{ on: riskView === 'member' }" @click="riskView = 'member'">按成员</button>
            <button type="button" class="chip" @click="setAllGroups(true)">全部展开</button>
            <button type="button" class="chip" @click="setAllGroups(false)">全部折叠</button>
          </div>
        </div>

        <div class="risk-groups">
          <section v-for="g in riskGroups" :key="g.key" class="risk-group" :class="'sev-' + g.severity">
            <header @click="toggleGroup(g)">
              <span class="caret">{{ isGroupOpen(g) ? '▾' : '▸' }}</span>
              <span class="g-title">{{ g.title }}</span>
              <span v-if="g.custom" class="g-badge" title="不在预设风险清单内,由模型基于证据提出">模型新增</span>
              <span class="g-count">{{ g.items.length }} 条</span>
              <span v-if="riskView === 'member'" class="g-mix">
                <i v-if="g.counts.high">高{{ g.counts.high }}</i>
                <i v-if="g.counts.medium">中{{ g.counts.medium }}</i>
                <i v-if="g.counts.low">低{{ g.counts.low }}</i>
              </span>
              <span v-else class="g-sev">{{ SEVERITY_TEXT[g.severity] }}</span>
            </header>
            <p v-if="g.hint && isGroupOpen(g)" class="g-hint small">💡 {{ g.hint }}</p>
            <ul v-if="isGroupOpen(g)">
              <li v-for="(r, i) in groupItems(g)" :key="i" class="risk-item">
                <div class="ri-head">
                  <span v-if="riskView === 'member'" class="ri-kind">
                    {{ metaOf(r.kind).icon }} {{ metaOf(r.kind).text }}
                  </span>
                  <span class="ri-who">
                    {{ r.member || '未分配' }}<template v-if="r.task_name"> · {{ r.task_name }}</template>
                  </span>
                </div>
                <p class="small">证据: {{ r.evidence }}</p>
                <p class="small">推断: {{ r.inference }}</p>
                <p class="small ri-act">建议: {{ r.suggested_action }}</p>
              </li>
            </ul>
            <button v-if="isGroupOpen(g) && g.items.length > ITEM_LIMIT" type="button" class="ghost ri-more"
                    @click="toggleOverflow(g)">
              {{ overflowOpen[g.key] ? `只看前 ${ITEM_LIMIT} 条` : `展开剩余 ${g.items.length - ITEM_LIMIT} 条` }}
            </button>
          </section>
        </div>
        <p v-if="!riskGroups.length" class="pool-note">该筛选条件下没有风险条目。</p>
      </template>
      <p v-else class="pool-note">当前时间范围内未发现团队级风险。</p>

      <div style="display:flex;align-items:center;gap:10px;margin-top:10px;flex-wrap:wrap">
        <button class="ghost" :disabled="runningLlm" @click="runLlmAnalysis">
          {{ runningLlm ? 'LLM 分析中…' : '▶ 运行 LLM 分析(消耗额度·落快照)' }}
        </button>
        <span class="small" style="opacity:.75">
          {{ isLlmRisks ? '当前风险结论来自大模型归因(risks_engine=llm)' : '预览不调 LLM;点此按钮才走模型归因' }}
        </span>
      </div>
    </template>

    <!-- 生成协调建议 → 送审(真实闭环) -->
    <div style="display:flex;align-items:center;gap:12px;margin-top:14px;flex-wrap:wrap">
      <button class="primary" id="submit-gen" :disabled="submitting" @click="submitSuggestions">
        {{ submitting ? '提交中…' : '⚖ 生成协调建议 → 送审(进入提案审核中心)' }}
      </button>
      <span class="small" style="opacity:.75">
        送审前将重新计算当前时间范围的团队风险,建议送审后前往「提案审核中心」人工确认
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
.sa-sync-dot.warn { background: #e05555; }
.sa-sync-dot.off { background: #b8860b; }
.sa-sync-ops { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-wrap: wrap; }
.sum-card h4 .warn { color: #b8860b; }
.commit-row .msg { flex: 1; }
.sa-filter { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sa-filter button { padding: 3px 9px; font-size: 11px; font-family: var(--mono); font-weight: 700; }
.sa-filter button.active { background: var(--green); }

/* ---------- 风险分组(分级 + 折叠 + 筛选) ---------- */
.risk-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.risk-chips, .risk-views { display: flex; gap: 6px; flex-wrap: wrap; }
.risk-views { margin-left: auto; }
.risk-bar .chip {
  padding: 3px 9px; font-size: 11px; font-family: var(--mono); font-weight: 700;
  border: 1px solid rgba(0,0,0,.18); background: transparent; border-radius: 999px; cursor: pointer;
}
.risk-bar .chip.on { background: var(--green); color: #fff; border-color: transparent; }
.risk-bar .chip.sev-high.on { background: #c62828; }
.risk-bar .chip.sev-medium.on { background: #b8860b; }
.risk-bar .chip.sev-low.on { background: #607d8b; }
.risk-groups { display: flex; flex-direction: column; gap: 8px; }
.risk-group { border: 1px solid rgba(0,0,0,.12); border-radius: 10px; overflow: hidden; }
.risk-group.sev-high { border-left: 3px solid #c62828; }
.risk-group.sev-medium { border-left: 3px solid #b8860b; }
.risk-group.sev-low { border-left: 3px solid #607d8b; }
.risk-group > header {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; cursor: pointer;
  background: rgba(0,0,0,.02);
}
.risk-group > header:hover { background: rgba(0,0,0,.05); }
.caret { font-size: 11px; opacity: .6; }
.g-title { font-weight: 700; font-size: 13px; }
.g-badge {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 999px;
  background: rgba(103,58,183,.12); color: #673ab7; border: 1px solid rgba(103,58,183,.35);
}
.g-count { font-size: 11px; font-family: var(--mono); opacity: .75; }
.g-sev, .g-mix { margin-left: auto; font-size: 11px; font-weight: 700; font-family: var(--mono); }
.g-mix { display: flex; gap: 7px; font-weight: 400; opacity: .8; }
.risk-group.sev-high .g-sev { color: #c62828; }
.risk-group.sev-medium .g-sev { color: #b8860b; }
.risk-group.sev-low .g-sev { color: #607d8b; }
.g-hint { margin: 0 10px 6px; opacity: .8; }
.risk-item { padding: 8px 12px; border-top: 1px solid rgba(0,0,0,.08); }
.risk-item p { margin: 2px 0; }
.ri-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 2px; }
.ri-kind { font-size: 12px; font-weight: 700; }
.ri-who { font-size: 12px; font-weight: 700; }
.ri-act { color: #2e7d32; }
.ri-more { margin: 6px 10px 10px; font-size: 11px; }
</style>
