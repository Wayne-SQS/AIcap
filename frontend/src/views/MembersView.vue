<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useProjectsStore } from '@/stores/projects'
import { projectsApi } from '@/api/projects'
import { useToast } from '@/composables/useToast'
import { membersApi } from '@/api/members'
import { profileAgentApi } from '@/api/profileAgent'
import { useProjectActions } from '@/composables/useProjectActions'
import MemberTaskDrawer from '@/components/members/MemberTaskDrawer.vue'
import TaskDetailDrawer from '@/components/members/TaskDetailDrawer.vue'
import OwnerRiskDialog from '@/components/members/OwnerRiskDialog.vue'
import { useCanvasZoom } from '@/composables/useCanvasZoom'
import CanvasZoomControls from '@/components/CanvasZoomControls.vue'
import { memberLabel, memberUserId } from '@/data/memberIdentity'

/* 成员任务图同时保留真实画像/GitHub 活动，以及任务负责人调整、影响确认和四个独立缩放画布。 */
const project = useProjectStore()
const projects = useProjectsStore()
const session = useSessionStore()
const { updateTaskOwner, analyzeTaskOwner } = useProjectActions()
const memberDrop = ref(null)
const drawerTask = ref(null)
const riskDialog = ref(null)
const pendingOwner = ref(null)
const draggingTask = ref('')
const editingCapacity = ref(null)
const capacityInput = ref('')
const capacityBusy = ref(false)
const { notify } = useToast()
function editCapacity(id) {
  editingCapacity.value = id
  capacityInput.value = String(project.weeklyCapacityHours(id))
}
async function saveCapacity() {
  const member = project.memberById(editingCapacity.value)
  if (!session.canReview || !member || !projects.currentProjectId) return
  const value = String(capacityInput.value ?? '').trim()
  if (value !== '' && (!/^\d+$/.test(value) || Number(value) > 168)) {
    notify('周容量请输入 0 到 168 的整数；留空恢复默认值')
    return
  }
  capacityBusy.value = true
  try {
    await projectsApi.updateMember(projects.currentProjectId, memberUserId(member.id), {
      member_role: member.roleKey,
      weekly_capacity_hours: value === '' ? null : Number(value)
    })
    await project.loadAll(projects.currentProjectId)
    editingCapacity.value = null
    notify('成员周容量已保存')
  } catch (error) { notify(error.message) }
  finally { capacityBusy.value = false }
}
const profiles = ref([])
const drawerMember = ref(null)
const online = computed(() => session.apiMode && !!session.currentUser)

const overviewViewport = ref(null)
const overviewShell = ref(null)
const overviewCanvas = ref(null)
const loadViewport = ref(null)
const loadShell = ref(null)
const loadCanvas = ref(null)
const capacityViewport = ref(null)
const capacityShell = ref(null)
const capacityCanvas = ref(null)
const activityViewport = ref(null)
const activityShell = ref(null)
const activityCanvas = ref(null)

async function loadProfiles() {
  if (!online.value) return
  try {
    profiles.value = await membersApi.profiles()
  } catch (e) {
    profiles.value = []
  }
}
const { zoom: overviewZoom, zoomMode: overviewZoomMode, minZoom: overviewMinZoom, maxZoom: overviewMaxZoom, canvasStyle: overviewCanvasStyle, shellStyle: overviewShellStyle, zoomIn: overviewZoomIn, zoomOut: overviewZoomOut, setZoomPercent: setOverviewZoomPercent, resetZoom: overviewResetZoom, fitToCanvas: overviewFitToCanvas } =
  useCanvasZoom({ viewport: overviewViewport, canvas: overviewCanvas, shell: overviewShell, fillViewportWidth: true, minContentWidth: 1040, initialMode: 'manual' })
const { zoom: loadZoom, zoomMode: loadZoomMode, minZoom: loadMinZoom, maxZoom: loadMaxZoom, canvasStyle: loadCanvasStyle, shellStyle: loadShellStyle, zoomIn: loadZoomIn, zoomOut: loadZoomOut, setZoomPercent: setLoadZoomPercent, resetZoom: loadResetZoom, fitToCanvas: loadFitToCanvas } =
  useCanvasZoom({ viewport: loadViewport, canvas: loadCanvas, shell: loadShell, fillViewportWidth: true, minContentWidth: 760, initialMode: 'manual' })
const { zoom: capacityZoom, zoomMode: capacityZoomMode, minZoom: capacityMinZoom, maxZoom: capacityMaxZoom, canvasStyle: capacityCanvasStyle, shellStyle: capacityShellStyle, zoomIn: capacityZoomIn, zoomOut: capacityZoomOut, setZoomPercent: setCapacityZoomPercent, resetZoom: capacityResetZoom, fitToCanvas: capacityFitToCanvas } =
  useCanvasZoom({ viewport: capacityViewport, canvas: capacityCanvas, shell: capacityShell, fillViewportWidth: true, minContentWidth: 760, initialMode: 'manual' })
const { zoom: activityZoom, zoomMode: activityZoomMode, minZoom: activityMinZoom, maxZoom: activityMaxZoom, canvasStyle: activityCanvasStyle, shellStyle: activityShellStyle, zoomIn: activityZoomIn, zoomOut: activityZoomOut, setZoomPercent: setActivityZoomPercent, resetZoom: activityResetZoom, fitToCanvas: activityFitToCanvas } =
  useCanvasZoom({ viewport: activityViewport, canvas: activityCanvas, shell: activityShell, fillViewportWidth: true, minContentWidth: 760, initialMode: 'manual' })

/* ==================== 成员卡片 ==================== */
const totalAllocated = computed(() => project.tasks.reduce((a, t) => a + Math.max(0, Number(t.h) || 0), 0))
const memberCards = computed(() => project.members.map(m => {
  const mine = project.memberTasks(m.id)
  const myH = project.allocatedHours(m.id)
  const state = project.capacityStatus(project.utilization(m.id))
  return {
    m, mine: mine.length, myH,
    pct: totalAllocated.value ? Math.round(myH / totalAllocated.value * 100) : 0,
    myStories: project.stories.filter(s => s.owner === m.id).length,
    state
  }
}))
function openTaskDrawer(id) { drawerTask.value = id }
function openMemberDrawer(id) { drawerMember.value = id }
function taskIdFromDrop(event) {
  return event.dataTransfer?.getData('application/x-aiguanli-task') || event.dataTransfer?.getData('text/plain') || ''
}
function weeklyHours(task) {
  const values = Array(6).fill(0)
  const start = Math.max(1, task.w[0])
  const end = Math.min(6, task.w[1])
  const span = Math.max(1, end - start + 1)
  const base = Math.floor(task.h / span)
  let remainder = task.h - base * span
  for (let week = start; week <= end; week++) values[week - 1] = base + (remainder-- > 0 ? 1 : 0)
  return values
}
function ownerRisks(task, ownerId) {
  const member = project.memberById(ownerId)
  const capacity = member ? project.weeklyCapacityHours(ownerId) : 0
  if (capacity == null) return []
  const current = project.memberWeeklyLoad(ownerId)
  const after = Array(6).fill(0)
  project.tasks.filter(item => item.owner === ownerId && item.id !== task.id).forEach(item => {
    weeklyHours(item).forEach((hours, index) => { after[index] += hours })
  })
  weeklyHours(task).forEach((hours, index) => { after[index] += hours })
  return after.flatMap((hours, index) => hours > capacity ? [{ type: 'load', text: `W${index + 1}：当前 ${current[index] || 0}h，调整后 ${hours}h，周容量 ${capacity.toFixed(1)}h` }] : [])
}
function startTaskDrag(task, event) {
  if (session.isViewer) return
  draggingTask.value = task.id
  event.dataTransfer.setData('application/x-aiguanli-task', task.id)
  event.dataTransfer.setData('text/plain', task.id)
  event.dataTransfer.effectAllowed = 'move'
}
function clearTaskDrag() {
  draggingTask.value = ''
  memberDrop.value = null
}
async function reassignTask({ taskId, ownerId }) {
  const task = project.taskById(taskId)
  const member = project.memberById(Number(ownerId))
  if (!task || !member || task.owner === member.id || session.isViewer) return
  const analysis = await analyzeTaskOwner(taskId, member.id)
  const risks = analysis.warnings.map(text => ({ type: 'load', text }))
  pendingOwner.value = { taskId, ownerId: member.id }
  riskDialog.value?.open({ taskId, memberName: member.name, risks, groups: analysis.groups })
}
function dropOnMember(ownerId, event) {
  const taskId = taskIdFromDrop(event)
  clearTaskDrag()
  if (taskId) reassignTask({ taskId, ownerId })
}
async function confirmOwnerChange() {
  const pending = pendingOwner.value
  pendingOwner.value = null
  if (pending) await updateTaskOwner(pending.taskId, pending.ownerId)
}
function cancelOwnerChange() { pendingOwner.value = null }

/* ==================== 周负载热力图 ==================== */
const load = computed(() => project.loadByOwner)
const LOAD_LABEL = ['低负载', '正常', '偏忙', '超载']
function heatCell(m, w) {
  const hours = load.value[m.id] ? load.value[m.id][w] : 0
  const weeklyCapacity = project.weeklyCapacityHours(m.id)
  const ratio = weeklyCapacity ? hours / weeklyCapacity : hours > 0 ? Infinity : 0
  const lv = ratio > 1 ? 3 : (ratio >= .8 ? 2 : (ratio >= .6 ? 1 : 0))
  const related = project.tasks.filter(t => t.owner === m.id && t.w[0] <= w + 1 && t.w[1] >= w + 1).map(t => t.id).join('、') || '无'
  return {
    hours, lv,
    title: `${m.name} · W${w + 1}\n计划工时：${hours}h\n周容量：${weeklyCapacity.toFixed(1)}h\n负载等级：${LOAD_LABEL[lv]}\n关联任务：${related}`
  }
}

/* ==================== 容量与分配 ==================== */
const bandwidthCards = computed(() => project.members.map(m => {
  const alloc = project.allocatedHours(m.id)
  const cap = project.capacityHours(m.id)
  const ratio = cap ? alloc / cap : alloc > 0 ? Infinity : 0
  return { m, alloc, cap, pct: Number.isFinite(ratio) ? Math.round(ratio * 100) : 100, state: project.capacityStatus(ratio) }
}))

/* ==================== 成员开发活动图(真实 activity_records + 任务规划预计强度) ==================== */
const DAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']
const contribLoading = ref(false)
const contribError = ref('')
const contribs = ref([])
const contribLoadVersion = ref(0)

function fmtDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
/** 最近 6 个完整自然周(周一起,恰好 42 天):与 6 周 × 7 天网格一一对应 */
function lastSixFullWeeks() {
  const now = new Date()
  const dow = (now.getDay() + 6) % 7
  const thisMonday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow)
  // 结束 = 上一个完整自然周的周日(本周尚未结束,不算"完整周")
  const end = new Date(thisMonday)
  end.setDate(thisMonday.getDate() - 1)
  // 起始 = 结束往前 42 天(周一起):start 落在周一,网格 42 格的行标签(一..日)才与真实星期对齐,
  // 且查询范围与渲染格数严格一致(此前 -41 会让整体错一天、并多查 6 天不计入格子却计入总数)
  const start = new Date(thisMonday)
  start.setDate(thisMonday.getDate() - 42)
  return { start: fmtDate(start), end: fmtDate(end) }
}
function contribTip(c, d, w) {
  if (c.mode === 'planned') {
    const meta = c.meta?.[d]?.[w]
    if (!meta || meta.hours <= 0) return `W${w + 1} 周${DAY_LABELS[d]} · 无预计工时`
    const levels = ['无活动', '少', '一般', '较多', '活跃']
    return `W${w + 1} 周${DAY_LABELS[d]} · 预计开发强度：${levels[meta.level]} · 预计工时约${meta.hours}h · 关联任务${meta.taskIds.join('、') || '无'}（预计值）`
  }
  const meta = c.meta[d][w]
  if (!meta || meta.total === 0) return `${c.m.name} · ${meta ? meta.date : '?'}\n无活动`
  const parts = Object.entries(meta.counts || {}).filter(([, n]) => n > 0).map(([t, n]) => `${t}×${n}`).join(' ')
  return `${c.m.name} · ${meta.date}\n活动 ${meta.total} 次${parts ? '\n' + parts : ''}\n来源:活动记录(含 GitHub 同步)`
}
function plannedWeeklyData(memberId) {
  const weekly = Array(6).fill(0)
  const taskIds = Array.from({ length: 6 }, () => [])
  project.tasks.filter(t => t.owner === memberId && Array.isArray(t.w)).forEach(task => {
    const start = Math.max(1, Number(task.w[0]) || 1)
    const end = Math.min(6, Number(task.w[1]) || start)
    const span = Math.max(1, end - start + 1)
    const hours = Math.max(0, Number(task.h) || 0)
    const base = Math.floor(hours / span)
    let remainder = hours - base * span
    for (let week = start; week <= end; week++) {
      weekly[week - 1] += base + (remainder-- > 0 ? 1 : 0)
      taskIds[week - 1].push(task.code || task.id)
    }
  })
  const weeklyCapacity = project.weeklyCapacityHours(memberId)
  const weeks = weekly.map((hours, index) => {
    const ratio = weeklyCapacity ? hours / weeklyCapacity : hours > 0 ? Infinity : 0
    const level = hours <= 0 ? 0 : (ratio > 1 ? 4 : (ratio >= .8 ? 3 : (ratio >= .6 ? 2 : 1)))
    return { week: index + 1, hours, level, taskIds: [...new Set(taskIds[index])] }
  })
  return { weekly, weeks }
}
function stableHash(value) {
  let hash = 2166136261
  for (const char of String(value)) {
    hash ^= char.charCodeAt(0)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}
function plannedGrid(memberId, plannedData, projectId) {
  const grid = Array.from({ length: 7 }, () => Array(6).fill(0))
  const meta = Array.from({ length: 7 }, () => Array(6).fill(null))
  plannedData.weeks.forEach((week, index) => {
    const hours = week.hours
    if (hours <= 0) return
    const seed = stableHash(`${projectId}:${memberId}:W${index + 1}:${week.taskIds.join(',')}`)
    // 先按周工时决定活跃天数，避免低工时周被机械铺满 6～7 天。
    // 有任务的周保持可读的活动痕迹：低/中/高工时分别覆盖约 2-3、3-4、4-5 个工作日。
    const variation = seed % 2
    const weekdayCount = hours <= 0 ? 0
      : (hours <= 3 ? 2
        : (hours <= 6 ? 2 + variation
          : (hours <= 12 ? 3 + variation
            : (hours <= 15 ? 4
              : (hours <= 20 ? 4 + variation : 5)))))
    const weekend = hours >= 29 && seed % 7 === 0
    const dayCount = Math.min(hours, 6, weekdayCount + (weekend ? 1 : 0))
    const candidates = weekend ? [0, 1, 2, 3, 4, 5, 6] : [0, 1, 2, 3, 4]
    const ordered = candidates.map((day, position) => ({ day, order: (seed + position * 13) % 101 })).sort((a, b) => a.order - b.order).slice(0, dayCount).map(item => item.day)
    const dayHours = Array(7).fill(0)
    // 每个活跃日先分配 1h，再按稳定顺序分配余量，形成有高有低的日强度。
    ordered.forEach(day => { dayHours[day] = 1 })
    const extraPattern = [0, 1, 0, 2, 1, 0, 3, 2, 1, 0, 4, 3, 2, 1, 0]
    for (let unit = ordered.length; unit < hours; unit++) {
      const offset = seed % extraPattern.length
      const target = extraPattern[(unit - ordered.length + offset) % extraPattern.length] % ordered.length
      dayHours[ordered[target]] += 1
    }
    ordered.forEach(day => {
      const dayHoursValue = dayHours[day]
      // 颜色按日工时区间映射，避免成员容量较小导致整周直接变成深绿色。
      const level = dayHoursValue >= 8 ? 4 : (dayHoursValue >= 5 ? 3 : (dayHoursValue >= 3 ? 2 : 1))
      grid[day][index] = Math.min(4, level)
      meta[day][index] = { date: `W${index + 1}`, total: level, hours: dayHoursValue, level, taskIds: week.taskIds }
    })
    for (let day = 0; day < 7; day++) if (!meta[day][index]) meta[day][index] = { date: `W${index + 1}`, total: 0, hours: 0, level: 0, taskIds: [] }
  })
  return { grid, meta }
}
async function loadContribs() {
  const loadVersion = ++contribLoadVersion.value
  const projectId = projects.currentProjectId
  if (!projectId) { contribs.value = []; contribError.value = ''; return }
  contribLoading.value = true
  contribError.value = ''
  const { start, end } = lastSixFullWeeks()
  try {
    // 成员范围以当前项目的 members 为准，避免活动或任务跨项目串入；画像仅用于补充真实姓名。
    const members = project.members.map(member => {
      const p = profiles.value.find(profile => Number(profile.user_id) === Number(memberUserId(member.id)))
      return { id: p?.user_id || memberUserId(member.id), name: p?.display_name || member.name, frontId: member.id }
    })
    const planned = new Map(members.map(m => [m.frontId, plannedWeeklyData(m.frontId)]))
    const nextContribs = await Promise.all(members.map(async m => {
      let rows = []
      if (online.value) {
        try { rows = await profileAgentApi.heatmap(start, end, m.id, projectId) } catch { rows = [] }
      }
      const dayMap = new Map(rows.map(r => [r.date, r]))
      const grid = Array.from({ length: 7 }, () => Array(6).fill(0))
      const meta = Array.from({ length: 7 }, () => Array(6).fill(null))
      const base = new Date(start + 'T00:00:00')
      for (let i = 0; i < 42; i++) {
        const d = new Date(base)
        d.setDate(base.getDate() + i)
        const key = fmtDate(d)
        const row = dayMap.get(key)
        const total = row ? row.total : 0
        const w = Math.floor(i / 7)
        const dd = i % 7
        grid[dd][w] = Math.min(total, 4)
        meta[dd][w] = row ? { date: key, total: row.total, counts: row.counts } : { date: key, total: 0, counts: null }
      }
      // 数字口径:真实活动总数/活跃天数取 heatmap 原始值;grid 仅用于格子颜色分级(单日 >4 统一最深色)
      const realTotal = rows.reduce((a, r) => a + r.total, 0)
      const realActive = rows.filter(r => r.total > 0).length
      if (realTotal > 0) return { m, rows: grid, meta, total: realTotal, activeDays: realActive, mode: 'real' }
      const expected = planned.get(m.frontId) || { weekly: Array(6).fill(0), weeks: [] }
      const plannedView = plannedGrid(m.frontId, expected, projectId)
      return {
        m,
        rows: plannedView.grid,
        meta: plannedView.meta,
        plannedWeeks: expected.weeks,
        total: expected.weekly.reduce((sum, hours) => sum + hours, 0),
        activeDays: expected.weekly.filter(hours => hours > 0).length,
        mode: expected.weekly.some(hours => hours > 0) ? 'planned' : 'empty'
      }
    }))
    if (loadVersion === contribLoadVersion.value && projectId === projects.currentProjectId) {
      contribs.value = nextContribs
    }
  } catch (e) {
    if (loadVersion === contribLoadVersion.value && projectId === projects.currentProjectId) {
      contribError.value = e.message || '加载真实活动数据失败'
      contribs.value = []
    }
  } finally {
    if (loadVersion === contribLoadVersion.value) contribLoading.value = false
  }
}
watch(online, async v => { if (v) { await loadProfiles(); await loadContribs() } }, { immediate: true })
watch(() => projects.currentProjectId, async (id, previousId) => {
  if (id === previousId) return
  contribLoadVersion.value += 1
  contribs.value = []
  contribError.value = ''
  if (online.value && id) await loadContribs()
})
// 新建项目或草案确认后任务列表可能在当前项目不变的情况下异步刷新，
// 预计热力图也必须随负责人/工时/周次变化重新计算。
watch(() => project.tasks, () => { if (projects.currentProjectId) loadContribs() }, { deep: true })
onMounted(async () => { await loadProfiles(); await loadContribs() })
</script>

<template>
  <main class="view" id="view-members">
    <div class="hero">
      <div>
        <div class="eyebrow">TEAM LOAD / 成员负载</div>
        <h1>成员任务图</h1>
        <p>{{ project.members.length }} 名成员（含只读查看者） · 6 周负载热力图 · 容量与分配 · 点击成员卡片查看任务明细。</p>
      </div>
    </div>

    <div class="member-canvas-list" aria-label="成员任务图模块">
    <section class="member-canvas-section members-overview-section" data-member-canvas="overview" aria-labelledby="members-overview-title">
      <header class="member-section-header">
        <h2 id="members-overview-title">成员任务总览</h2>
        <p>成员任务、负载摘要与当前分配情况；点击任务查看明细，拖动任务可调整负责人。</p>
      </header>
      <CanvasZoomControls :zoom="overviewZoom" :min-zoom="overviewMinZoom" :max-zoom="overviewMaxZoom" :zoom-mode="overviewZoomMode"
        :reset-zoom="overviewResetZoom" :fit-to-canvas="overviewFitToCanvas" :zoom-in="overviewZoomIn" :zoom-out="overviewZoomOut"
        :set-zoom-percent="setOverviewZoomPercent" />
      <div ref="overviewViewport" class="member-module-viewport canvas-zoom-viewport overview-viewport">
        <div ref="overviewShell" class="canvas-zoom-shell" :style="overviewShellStyle">
          <div ref="overviewCanvas" class="member-module-canvas overview-canvas" :style="overviewCanvasStyle">
    <div class="member-grid" id="member-grid">
      <div
        v-for="c in memberCards" :key="c.m.id"
        class="mcard clickable" :class="{ 'is-drop-target': memberDrop === c.m.id }"
        :data-member="c.m.id" :data-owner-drop="c.m.id"
        @dragenter.prevent="memberDrop = c.m.id" @dragover.prevent="memberDrop = c.m.id"
        @dragleave.self="memberDrop = null" @drop.prevent.stop="dropOnMember(c.m.id, $event)" @click="openMemberDrawer(c.m.id)"
      >
        <div class="head">
          <span class="avatar" :class="'a' + c.m.id">{{ memberLabel(c.m.id) }}</span>
          <div><h3>{{ c.m.name }}</h3><div class="role">{{ project.memberRoleText(c.m.id) }}</div></div>
          <span class="loadflag" :class="c.state.key" style="margin-left:auto">{{ c.state.label }}</span>
        </div>
        <div class="tagline" style="font-size:12px;color:var(--muted);margin-bottom:12px">{{ c.m.tag }}</div>
        <div class="statline"><span>任务 <b>{{ c.mine }}</b></span><span>工时 <b>{{ c.myH }}h</b></span><span>占比 <b>{{ c.pct }}%</b></span><span>故事 <b>{{ c.myStories }}</b></span></div>
        <div class="track"><i :style="{ width: Math.min(c.pct, 100) + '%', background: `var(--${c.m.accent || 'gray'})` }"></i></div>
        <div class="member-task-heading">当前任务</div>
        <div class="member-task-list">
          <button
            v-for="task in project.memberTasks(c.m.id)" :key="task.id" type="button"
            class="member-task-card" :class="{ 'is-dragging': draggingTask === task.id }"
            :draggable="!session.isViewer"
            :title="session.isViewer ? '只读角色不可调整负责人' : '拖动到其他成员卡片以调整负责人'"
            @click.stop="openTaskDrawer(task.id)" @dragstart.stop="startTaskDrag(task, $event)" @dragend="clearTaskDrag"
          >
            <span class="member-task-id">{{ task.id }}</span>
            <span class="member-task-name" :title="task.name">{{ task.name }}</span>
            <span class="member-task-meta">{{ task.h }}h · Sprint {{ project.taskSprintList(task).join('/') }} · {{ project.taskStatusText(task) }}</span>
          </button>
          <div v-if="!project.memberTasks(c.m.id).length" class="member-task-empty">暂无任务</div>
        </div>
      </div>
    </div>
          </div>
        </div>
      </div>
    </section>

    <section class="member-canvas-section workload-heatmap-section" data-member-canvas="load" aria-labelledby="workload-heatmap-title">
    <header class="member-section-header">
      <h2 id="workload-heatmap-title">周负载热力图</h2>
      <p class="load-note">数字表示该成员在该周的计划工时；颜色表示该周负载等级（按周容量占比）。0 表示该周暂无计划工时，悬停可看关联任务。</p>
    </header>
    <CanvasZoomControls :zoom="loadZoom" :min-zoom="loadMinZoom" :max-zoom="loadMaxZoom" :zoom-mode="loadZoomMode"
      :reset-zoom="loadResetZoom" :fit-to-canvas="loadFitToCanvas" :zoom-in="loadZoomIn" :zoom-out="loadZoomOut"
      :set-zoom-percent="setLoadZoomPercent" />
    <div ref="loadViewport" class="member-module-viewport canvas-zoom-viewport load-viewport">
      <div ref="loadShell" class="canvas-zoom-shell" :style="loadShellStyle">
        <div ref="loadCanvas" class="member-module-canvas load-canvas" :style="loadCanvasStyle">
    <div class="load-legend">
      <span><i class="low"></i>低负载</span><span><i class="normal"></i>正常</span><span><i class="busy"></i>偏忙</span><span><i class="over"></i>超载</span>
    </div>
    <div class="heat-wrap">
      <div class="heat" id="heat">
        <div class="heat-head"><div>成员 \ 周</div><div v-for="w in 6" :key="w">W{{ w }}</div></div>
        <div v-for="m in project.members" :key="m.id" class="heat-row">
          <div class="heat-name"><span class="avatar" :class="'a' + m.id">{{ memberLabel(m.id) }}</span>{{ m.name }}</div>
          <div v-for="w in 6" :key="w" class="heat-cell" :title="heatCell(m, w - 1).title"><span class="hrs" :class="'hl' + heatCell(m, w - 1).lv">{{ heatCell(m, w - 1).hours }}</span></div>
        </div>
      </div>
    </div>
        </div>
      </div>
    </div>
    </section>

    <section class="member-canvas-section bandwidth-section" data-member-canvas="capacity" aria-labelledby="bandwidth-title">
    <header class="member-section-header">
      <h2 id="bandwidth-title">容量与分配</h2>
      <p>Bandwidth · 对比每位成员的可用容量、已分配工时与剩余空间。</p>
    </header>
    <CanvasZoomControls :zoom="capacityZoom" :min-zoom="capacityMinZoom" :max-zoom="capacityMaxZoom" :zoom-mode="capacityZoomMode"
      :reset-zoom="capacityResetZoom" :fit-to-canvas="capacityFitToCanvas" :zoom-in="capacityZoomIn" :zoom-out="capacityZoomOut"
      :set-zoom-percent="setCapacityZoomPercent" />
    <div ref="capacityViewport" class="member-module-viewport canvas-zoom-viewport capacity-viewport">
      <div ref="capacityShell" class="canvas-zoom-shell" :style="capacityShellStyle">
        <div ref="capacityCanvas" class="member-module-canvas capacity-canvas" :style="capacityCanvasStyle">
    <div id="bandwidth">
      <div v-for="b in bandwidthCards" :key="b.m.id" class="mcard">
        <div class="head">
          <span class="avatar" :class="'a' + b.m.id">{{ memberLabel(b.m.id) }}</span>
          <div><h3>{{ b.m.name }}</h3><div class="role" :title="`每周 ${project.weeklyCapacityHours(b.m.id)}h × ${project.projectWeeks} 周 = 总容量 ${b.cap}h`">容量 {{ b.cap }}h · 已分配 {{ b.alloc }}h · 剩余 {{ b.cap - b.alloc }}h</div></div>
          <span class="loadflag" :class="b.state.key" style="margin-left:auto">{{ b.state.label }}</span>
        </div>
        <button v-if="session.canReview" type="button" @click="editCapacity(b.m.id)">设置周容量</button>
        <form v-if="editingCapacity === b.m.id" class="capacity-form" @submit.prevent="saveCapacity">
          <label>人时/周 <input v-model="capacityInput" type="number" min="0" max="168" placeholder="留空恢复默认"></label>
          <button :disabled="capacityBusy" type="submit">保存</button>
          <button type="button" @click="editingCapacity = null">取消</button>
        </form>
        <div class="bw-row"><span class="small">已分配</span><div class="bw-track"><i class="used" :class="b.state.key" :style="{ width: Math.min(b.pct, 100) + '%' }"></i><i class="cap" style="left:100%"></i></div><span class="mono small">{{ b.pct }}%</span></div>
      </div>
    </div>
        </div>
      </div>
    </div>
    </section>

    <section class="member-canvas-section activity-heatmap-section" data-member-canvas="activity" aria-labelledby="activity-heatmap-title">
    <header class="member-section-header">
      <h2 id="activity-heatmap-title">成员开发活动图</h2>
      <p>按周查看活动记录或预计工时。</p>
    </header>
    <CanvasZoomControls :zoom="activityZoom" :min-zoom="activityMinZoom" :max-zoom="activityMaxZoom" :zoom-mode="activityZoomMode"
      :reset-zoom="activityResetZoom" :fit-to-canvas="activityFitToCanvas" :zoom-in="activityZoomIn" :zoom-out="activityZoomOut"
      :set-zoom-percent="setActivityZoomPercent" />
    <div ref="activityViewport" class="member-module-viewport canvas-zoom-viewport activity-viewport">
      <div ref="activityShell" class="canvas-zoom-shell" :style="activityShellStyle">
        <div ref="activityCanvas" class="member-module-canvas activity-canvas" :style="activityCanvasStyle">
          <p v-if="!online" class="small">登录后查看活动记录。</p>
          <p v-else-if="contribLoading" class="small">正在加载活动数据…</p>
          <p v-else-if="contribError" class="small" role="alert">活动数据加载失败：{{ contribError }}</p>
          <div v-else class="contrib-wrap" id="contrib">
      <div v-for="c in contribs" :key="c.m.id" class="contrib">
        <div class="chead">
          <span class="avatar" :class="'a' + c.m.frontId">{{ memberLabel(c.m.frontId) }}</span>{{ c.m.name }}
          <span class="small mono" style="margin-left:auto" v-if="c.mode === 'real'">活动 {{ c.total }} · 活跃天数 {{ c.activeDays }}</span>
          <span class="small mono" style="margin-left:auto" v-else-if="c.mode === 'planned'">预计工时 {{ c.total }}h · 计划周数 {{ c.activeDays }}</span>
          <span class="small mono" style="margin-left:auto" v-else>暂无活动或任务规划</span>
        </div>
        <div class="contrib-role small">{{ project.memberRoleText(c.m.frontId) }}</div>
        <div v-if="c.mode === 'real' || c.mode === 'planned'" class="contrib-grid">
          <span class="grid-corner"></span>
          <span v-for="w in 6" :key="'h' + w" class="week-label" :style="{ gridColumn: w + 1, gridRow: 1 }">W{{ w }}</span>
          <template v-for="(row, d) in c.rows" :key="'d' + d">
            <span class="day-label" :style="{ gridColumn: 1, gridRow: d + 2 }">{{ DAY_LABELS[d] }}</span>
            <span
              v-for="(v, w) in row" :key="'c' + d + '-' + w"
              class="c-cell" :class="'c' + v"
              :style="{ gridColumn: w + 2, gridRow: d + 2 }"
              :title="contribTip(c, d, w)"
            ></span>
          </template>
        </div>
      </div>
      <div class="contrib-note">
        <span>颜色越深，活动或预计工时越多</span>
        <span>无活动</span><span class="sw c0"></span>
        <span>少</span><span class="sw c1"></span>
        <span>一般</span><span class="sw c2"></span>
        <span>较多</span><span class="sw c3"></span>
        <span>活跃</span><span class="sw c4"></span>
      </div>
    </div>
        </div>
      </div>
    </div>
    </section>
    </div>
    <MemberTaskDrawer v-if="drawerMember !== null" :member-id="drawerMember" @close="drawerMember = null" @reassign="reassignTask" />
    <TaskDetailDrawer v-if="drawerTask !== null" :task-id="drawerTask" @close="drawerTask = null" />
    <OwnerRiskDialog ref="riskDialog" @cancel="cancelOwnerChange" @confirm="confirmOwnerChange" />
  </main>
</template>

<style scoped>
/* Each module is a complete canvas unit: heading, toolbar, viewport and content. */
.member-canvas-list {
  display: grid;
  gap: 30px;
  width: 100%;
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  box-sizing: border-box;
}

.member-canvas-section {
  display: block;
  width: 100%;
  min-width: 0;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.member-section-header {
  display: block;
  margin: 0 0 8px;
  padding: 0 0 8px;
  border-bottom: 1px dashed var(--line);
  background: transparent;
}

.member-section-header h2 {
  margin: 0;
  font-size: 14px;
  font-family: var(--mono);
  letter-spacing: 1px;
  line-height: 1.25;
}

.member-section-header p {
  max-width: 860px;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.member-canvas-section > .canvas-zoom-toolbar {
  margin-bottom: 8px;
}

.member-module-viewport {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  overflow: auto;
  overscroll-behavior: auto;
  border: 2px solid var(--ink);
  background: var(--paper);
  box-shadow: var(--shadow);
  scrollbar-gutter: stable;
}

.member-module-viewport > .canvas-zoom-shell {
  background: transparent;
  border: 0;
  box-shadow: none;
}

.member-module-canvas {
  display: block;
  box-sizing: border-box;
  padding: 14px;
  background: var(--paper);
}

.overview-viewport {
  height: clamp(560px, 68vh, 760px);
}

.load-viewport {
  height: 390px;
}

.capacity-viewport {
  height: clamp(480px, 62vh, 620px);
}

.activity-viewport {
  height: clamp(580px, 68vh, 720px);
}

.members-overview-section .member-grid {
  gap: 14px;
}

.workload-heatmap-section .load-legend {
  margin: 0 0 10px;
}

.workload-heatmap-section .heat-wrap {
  border: 1px solid var(--line);
  box-shadow: none;
}

.bandwidth-section #bandwidth {
  gap: 10px;
}

.bandwidth-section .mcard {
  box-shadow: none;
}
.capacity-form { display:flex;align-items:end;gap:8px;flex-wrap:wrap;margin-top:8px }
.capacity-form label { display:grid;gap:4px;font-size:12px }
.capacity-form input { width:150px;padding:6px;border:2px solid var(--ink) }

.activity-heatmap-section .contrib-wrap {
  gap: 12px;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.activity-heatmap-section .contrib-note {
  margin-top: 2px;
  padding: 10px 0 0;
  border-top: 1px dashed var(--line);
}

@media (max-width: 720px) {
  .member-canvas-list {
    gap: 24px;
  }

  .member-section-header {
    margin-bottom: 7px;
    padding-bottom: 7px;
  }

  .member-module-canvas {
    padding: 10px;
  }

  .overview-viewport,
  .capacity-viewport,
  .activity-viewport {
    height: 560px;
  }

  .load-viewport {
    height: 360px;
  }

}
</style>
