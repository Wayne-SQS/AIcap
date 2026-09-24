<script setup>
import { computed, ref } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useAgentRefresh, completingTaskId } from '@/composables/useAgentRefresh'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { MILESTONES } from '@/data/seed'
import { memberLabel } from '@/data/memberIdentity'
import { useProjectActions } from '@/composables/useProjectActions'
import { useToast } from '@/composables/useToast'
import GanttRow from '@/components/gantt/GanttRow.vue'
import TaskEditorDialog from '@/components/gantt/TaskEditorDialog.vue'
import RiskConfirmDialog from '@/components/gantt/RiskConfirmDialog.vue'
import StoryEditorDialog from '@/components/board/StoryEditorDialog.vue'
import { useCanvasZoom } from '@/composables/useCanvasZoom'
import CanvasZoomControls from '@/components/CanvasZoomControls.vue'

/* 甘特图任务视图:
   - 每个任务占一行，Story 血缘保留在任务元数据与详情面板中
   - Sprint 分带表头、任务排期条、依赖高亮、任务详情和真实编辑能力保持不变
   - 任务排期拖拽继续走影响分析与风险确认流程 */
const project = useProjectStore()
const session = useSessionStore()
const sprintLabel = ['', 'S1', 'S1', 'S2', 'S2', 'S3', 'S3']
const sprintOf = w => Math.ceil(w / 2)
/* 只读账号(viewer)的编辑入口保持可见但禁用,title 说明原因 */
const viewerTitle = computed(() => (session.isViewer ? READONLY_TITLE : ''))
/* 任务完成触发画像智能体；排期调整保留影响分析与确认。 */
const { completeTask } = useAgentRefresh()
const doneTitle = computed(() => (session.isViewer ? READONLY_TITLE : '标记完成并触发任务提交智能体'))
const { updateTaskSchedule, analyzeTaskSchedule, isSaving } = useProjectActions()
const { notify } = useToast()

const selection = ref(null)          // { type:'task'|'story', id }
const taskEditorRef = ref(null)
const riskDialogRef = ref(null)
const storyEditorRef = ref(null)
const pendingRisk = ref(null)
const schedulePreview = ref(null)
const ganttViewport = ref(null)
const ganttShell = ref(null)
const ganttCanvas = ref(null)
const { zoom, zoomMode, minZoom, maxZoom, canvasStyle, shellStyle, zoomIn, zoomOut, setZoomPercent, resetZoom, fitToCanvas: fitGanttCanvas } = useCanvasZoom({
  viewport: ganttViewport,
  canvas: ganttCanvas,
  shell: ganttShell,
  fillViewportWidth: true,
  minContentWidth: 1040,
  fitMinZoom: 0.2,
  fitPadding: 0,
  fitAlign: 'start',
  initialMode: 'manual'
})

function fitToCanvas() {
  fitGanttCanvas()
}

const selectedTaskId = computed(() => (selection.value?.type === 'task' ? selection.value.id : null))
const selectedStoryId = computed(() => (selection.value?.type === 'story' ? selection.value.id : null))
const selectedTask = computed(() => (selectedTaskId.value ? project.taskById(selectedTaskId.value) : null))
const selectedStory = computed(() => (selectedStoryId.value ? project.stories.find(s => s.id === selectedStoryId.value) : null))
const predecessors = computed(() => (selectedTaskId.value ? project.predecessorsOf(selectedTaskId.value) : []))
const dependents = computed(() => (selectedTaskId.value ? project.dependentsOf(selectedTaskId.value) : []))
const relationOf = id => {
  if (!selectedTaskId.value || id === selectedTaskId.value) return ''
  if (predecessors.value.includes(id)) return 'predecessor'
  if (dependents.value.includes(id)) return 'dependent'
  return ''
}
function select(key) {
  if (!key) return
  const [type, id] = key.split(':')
  // 再次点击同一条目 = 取消选中(避免详情面板一直占位)
  if (selection.value && selection.value.type === type && selection.value.id === id) selection.value = null
  else selection.value = { type, id }
}
function selectStory(id) { selection.value = { type: 'story', id } }
function selectTask(id) { selection.value = { type: 'task', id } }

/* 任务条:计划起止范围(长度=周数,不按工时比例);状态/进度写在 title 里 */
function barOf(t, key) {
  const preview = schedulePreview.value?.taskId === t.id ? schedulePreview.value : null
  const start = preview?.start ?? t.w[0]
  const end = preview?.end ?? t.w[1]
  const span = end - start + 1
  const cross = sprintOf(start) !== sprintOf(end)
  const relation = relationOf(t.id)
  const cls = [`o${t.owner}`]
  if (t.type === 'management') cls.push('mgmt')
  else if (t.status === 2 || t.progress >= 100) cls.push('task-done')
  if (relation) cls.push(relation)
  return {
    left: ((start - 1) / 6 * 100).toFixed(2),
    width: (span / 6 * 100).toFixed(2),
    title: `${t.id} ${t.name} · ${t.h}h · ${project.memberName(t.owner)} · ${project.taskSprintText(t)} · ${project.taskStatusText(t)}（进度 ${t.progress || 0}%）${cross ? ' · 跨迭代实施(横跨两个 Sprint)' : ''}`,
    cls: cls.join(' '),
    label: t.id,
    pct: null,
    key,
    selected: selectedTaskId.value === t.id,
    relation
  }
}

function metaOf(t) {
  const stories = taskRefs(t.story)
  const storySummary = stories.length > 1 ? `${stories[0]} +${stories.length - 1}` : (stories[0] || '未关联')
  return [
    { text: t.id, cls: 'taskid' },
    { text: project.memberName(t.owner), cls: 'ownerref' },
    { text: t.h + 'h' },
    { text: project.taskSprintText(t) },
    { text: storySummary, cls: 'storyref', title: t.story || '未关联 Story' }
  ]
}

const taskRows = computed(() => [...project.tasks].sort((a, b) =>
  String(a.id).localeCompare(String(b.id), undefined, { numeric: true })
))

const milestoneLayout = computed(() => {
  const lanesByWeek = new Map()
  return project.milestones.map(milestone => {
    const week = Math.min(6, Math.max(1, Number(milestone.week) || 1))
    const lane = lanesByWeek.get(week) || 0
    lanesByWeek.set(week, lane + 1)
    return { ...milestone, week, lane }
  })
})
const milestoneLaneCount = computed(() => Math.max(
  1,
  ...milestoneLayout.value.map(milestone => milestone.lane + 1)
))
const milestoneTrackStyle = computed(() => ({
  '--milestone-lanes': milestoneLaneCount.value
}))

/* 子任务所属 Story 的 Sprint(详情面板用) */
const storySprintText = computed(() => {
  const t = selectedTask.value
  if (!t) return '未关联'
  const ids = taskRefs(t.story)
  if (!ids.length) return '未关联'
  return ids.map(id => {
    const s = project.stories.find(x => x.id === id)
    return s ? `${id} · S${s.sprint}` : id
  }).join('、')
})
const cardProgressOf = computed(() => {
  const t = selectedTask.value
  if (!t || !t.card) return null
  const s = project.stories.find(x => x.id === t.card)
  return { card: t.card, title: s ? s.title : '', pct: Math.round(project.cardPct(s || { status: 0, id: t.card }) * 100) }
})
const storySubs = computed(() => (selectedStoryId.value ? project.subTasksOf(selectedStoryId.value) : []))

function hoursByWeek(task, start, end) {
  const out = Array(6).fill(0), count = end - start + 1, base = Math.floor(task.h / count)
  let remainder = task.h - base * count
  for (let w = start; w <= end; w++) out[w - 1] = base + (remainder-- > 0 ? 1 : 0)
  return out
}
function scheduleWarnings(task, start, end) {
  const warnings = []
  project.predecessorsOf(task.id).forEach(id => {
    const predecessor = project.taskById(id)
    if (predecessor && predecessor.w[1] > start) warnings.push(`${id} 在 W${predecessor.w[1]} 结束，晚于新开始周 W${start}`)
  })
  project.dependentsOf(task.id).forEach(id => {
    const dependent = project.taskById(id)
    if (dependent && dependent.w[0] < end) warnings.push(`${id} 在 W${dependent.w[0]} 开始，早于新结束周 W${end}`)
  })
  const sprints = new Set(project.taskSprintList({ ...task, w: [start, end], sprints: [] }))
  if (project.storiesForTask(task).some(story => !sprints.has(story.sprint))) warnings.push('新排期与关联 Story Sprint 不完全一致')
  const other = project.tasks.filter(t => t.owner === task.owner && t.id !== task.id)
  const proposed = hoursByWeek(task, start, end)
  const weeklyCapacity = project.capacityHours(task.owner) / 6
  for (let week = 1; week <= 6; week++) {
    const load = other.reduce((sum, item) => sum + hoursByWeek(item, item.w[0], item.w[1])[week - 1], 0) + proposed[week - 1]
    if (weeklyCapacity && load > weeklyCapacity) warnings.push(`W${week} 预计 ${load}h，超过周容量 ${weeklyCapacity.toFixed(1)}h`)
  }
  return [...new Set(warnings)]
}
function groupScheduleWarnings(warnings) {
  const grouped = {
    dependency: { type: 'dependency', title: '任务依赖冲突', items: [] },
    load: { type: 'load', title: '成员负载超限', items: [] },
    sprint: { type: 'sprint', title: 'Sprint 边界冲突', items: [] },
    other: { type: 'other', title: '其他影响', items: [] }
  }
  warnings.forEach(warning => {
    if (/^W\d+ 预计/.test(warning)) grouped.load.items.push(warning)
    else if (warning.includes('Story Sprint')) grouped.sprint.items.push(warning)
    else if (/^T\d+ 在 W\d+/.test(warning)) grouped.dependency.items.push(warning)
    else grouped.other.items.push(warning)
  })
  return Object.values(grouped).filter(group => group.items.length)
}
async function confirmRiskSave() {
  const pending = pendingRisk.value
  pendingRisk.value = null
  if (!pending) {
    schedulePreview.value = null
    return
  }
  try {
    await updateTaskSchedule(pending.taskId, { weekStart: pending.start, weekEnd: pending.end })
  } finally {
    schedulePreview.value = null
  }
}
function cancelRiskSave() {
  pendingRisk.value = null
  schedulePreview.value = null
}
async function onScheduleChange({ key, mode, deltaWeeks }) {
  const [, taskId] = key.split(':')
  const task = project.taskById(taskId)
  if (!task || session.isViewer) return
  let start = task.w[0], end = task.w[1]
  if (mode === 'move') { start += deltaWeeks; end += deltaWeeks }
  else if (mode === 'resize-start') start += deltaWeeks
  else end += deltaWeeks
  if (start < 1 || end > 6 || start > end) {
    notify('不能放置：任务排期必须位于 W1-W6，且至少持续一周')
    return
  }
  schedulePreview.value = { taskId, start, end }
  try {
    const analysis = await analyzeTaskSchedule(taskId, start, end)
    const warnings = analysis.warnings
    pendingRisk.value = { taskId, start, end, groups: analysis.groups, total: warnings.length }
    riskDialogRef.value?.open(pendingRisk.value)
  } catch (error) {
    schedulePreview.value = null
    notify(error?.message || '排期影响分析失败，未保存调整')
  }
}
</script>

<template>
  <section class="view" id="view-gantt">
    <div class="hero">
      <div>
        <div class="eyebrow">GANTT CHART / 6 周排期 · 强制血缘</div>
        <h1>甘特图</h1>
        <p>任务按计划周展示，保留 Story 血缘、负责人、优先级与管理任务语义；点击任务条可查看依赖并编辑排期。</p>
      </div>
      <span class="demo">Sprint 1 = W1–W2 · Sprint 2 = W3–W4 · Sprint 3 = W5–W6</span>
    </div>
    <CanvasZoomControls :zoom="zoom" :min-zoom="minZoom" :max-zoom="maxZoom" :zoom-mode="zoomMode"
      :reset-zoom="resetZoom" :fit-to-canvas="fitToCanvas" :zoom-in="zoomIn" :zoom-out="zoomOut"
      :set-zoom-percent="setZoomPercent" />
    <div ref="ganttViewport" class="gantt-wrap">
      <div ref="ganttShell" class="gantt-zoom-shell" :style="shellStyle">
        <div ref="ganttCanvas" class="gantt" id="gantt" :style="canvasStyle">
        <div class="gantt-head gantt-sprint">
          <div class="taskh">执行任务 / 关联 Story</div>
          <div class="sprinth s1">Sprint 1 · 基础闭环</div>
          <div class="sprinth s2">Sprint 2 · 会议与协同</div>
          <div class="sprinth s3">Sprint 3 · GitHub 与 AI</div>
        </div>
        <div class="gantt-head">
          <div class="taskh">计划窗口（W1–W6）</div>
          <div v-for="w in 6" :key="w" class="wk">W{{ w }}<small>{{ sprintLabel[w] }}</small></div>
        </div>
        <GanttRow
          v-for="t in taskRows"
          :key="t.id"
          variant="task"
          :title="t.name"
          :meta="metaOf(t)"
          :bar="barOf(t, `task:${t.id}`)"
          :draggable="!session.isViewer"
          :saving="isSaving(`task:${t.id}`)"
          @select="select"
          @schedule-change="onScheduleChange"
        />
        <div class="mile-row">
          <div class="mile-title">◆ 项目里程碑</div>
          <div class="mile-track" :style="milestoneTrackStyle">
            <div
              v-for="m in milestoneLayout"
              :key="m.id"
              class="milestone"
              :data-milestone-id="m.id"
              :data-week="m.week"
              :title="m.desc"
              :style="{ gridColumn: m.week, gridRow: m.lane + 1 }"
            >
              <span class="diamond"></span><span>{{ m.id }} {{ m.name }}</span>
            </div>
          </div>
        </div>
        </div>
      </div>
    </div>

    <div class="legend" id="gantt-legend">
      <span v-for="m in project.members" :key="m.id" class="chip">
        <span class="sw" :style="{ background: `var(--${m.accent || ['green', 'orange', 'blue', 'pink', 'gray'][m.id]})` }"></span>{{ memberLabel(m.id) }} {{ m.name }}
      </span>
      <span class="chip"><span class="sw" style="background:var(--paper-2);border:1px dashed var(--muted)"></span>◇ 管理任务</span>
      <span class="chip"><span class="diamond-key"></span>菱形 = 里程碑</span>
      <span class="chip note">W1–W6 = 周次 · S1–S3 = Sprint</span>
      <span class="chip note">任务条 = 计划起止范围（不按工时比例）</span>
      <span class="chip note">点击任务条查看前置 / 后续任务</span>
    </div>

    <!-- 详情面板:选中任务 → 任务详情 + 编辑任务;从任务详情可继续查看 Story -->
    <div class="gantt-detail" :class="{ empty: !selection }" id="gantt-detail">
      <template v-if="!selection">
        点击任务条可查看关联故事、前置任务与后续任务。任务条表示计划起止时间范围，不按工时比例绘制。
      </template>

      <template v-else-if="selectedTask">
        <div class="detail-head">
          <span class="id">{{ selectedTask.id }}</span>
          <h3>{{ selectedTask.name }}</h3>
          <div class="actions">
            <button type="button" class="primary" :disabled="session.isViewer" :title="viewerTitle" @click="taskEditorRef?.open(selectedTask.id)">编辑任务</button>
            <button
              v-if="project.taskStatusKey(selectedTask) !== 'done'"
              type="button"
              :data-task-done="selectedTask.id"
              :disabled="session.isViewer || completingTaskId === selectedTask.id"
              :title="doneTitle"
              @click="completeTask(selectedTask)"
            >{{ completingTaskId === selectedTask.id ? '提交中…' : '✓ 标记已完成' }}</button>
            <button v-if="selectedTask.card" type="button" @click="selectStory(selectedTask.card)">查看父卡 {{ selectedTask.card }}</button>
          </div>
        </div>
        <div class="detail-grid">
          <div><b>负责人 / 工时</b><span>{{ project.memberName(selectedTask.owner) }} · {{ selectedTask.h }}h</span></div>
          <div><b>优先级</b><span>{{ selectedTask.priority || 'Should' }}</span></div>
          <div><b>执行排期 / Sprint</b><span>W{{ selectedTask.w[0] }}–W{{ selectedTask.w[1] }} · {{ project.taskSprintText(selectedTask) }}</span></div>
          <div><b>看板血缘 / 类型</b><span>{{ selectedTask.card || '不挂卡' }} · {{ selectedTask.type }}</span></div>
          <div><b>状态 / 进度</b><span>{{ project.taskStatusText(selectedTask) }} · {{ selectedTask.progress || 0 }}%</span></div>
          <div><b>关联 Story</b><span>{{ selectedTask.story || '未关联' }}</span></div>
          <div><b>Story 所属 Sprint</b><span>{{ storySprintText }}</span></div>
          <div><b>前置任务</b><span>{{ predecessors.length ? predecessors.map(project.taskName).join('；') : '无' }}</span></div>
          <div><b>后续任务</b><span>{{ dependents.length ? dependents.map(project.taskName).join('；') : '无' }}</span></div>
          <div><b>父卡加权进度</b><span>{{ cardProgressOf ? `${cardProgressOf.card} ${cardProgressOf.title} · ${cardProgressOf.pct}%` : '—' }}</span></div>
        </div>
      </template>

      <template v-else-if="selectedStory">
        <div class="detail-head">
          <span class="id">{{ selectedStory.id }}</span>
          <h3>{{ selectedStory.title }}</h3>
          <div class="actions">
            <button type="button" class="primary" :disabled="session.isViewer" :title="viewerTitle" @click="storyEditorRef?.open(selectedStory.id)">编辑故事</button>
          </div>
        </div>
        <div class="detail-grid">
          <div><b>看板卡 / 状态</b><span>{{ selectedStory.id }} · {{ ['待办', '进行中', '已完成'][selectedStory.status] }}</span></div>
          <div><b>负责人 / Sprint</b><span>{{ project.memberName(selectedStory.owner) }} · S{{ selectedStory.sprint }}</span></div>
          <div><b>工时加权进度</b><span>{{ Math.round(project.cardPct(selectedStory) * 100) }}%</span></div>
        </div>
        <div class="detail-sub">
          <b>子任务（{{ storySubs.length }}）</b>
          <div style="margin-top:6px">
            <button
              v-for="t in storySubs" :key="t.id" type="button" class="task-chip"
              @click="selectTask(t.id)"
            >{{ t.id }} · {{ t.h }}h · {{ project.taskStatusText(t) }}</button>
            <span v-if="!storySubs.length" class="small">该卡暂无子任务（进度按状态折算）</span>
          </div>
        </div>
      </template>
    </div>

    <TaskEditorDialog ref="taskEditorRef" />
    <RiskConfirmDialog ref="riskDialogRef" @cancel="cancelRiskSave" @confirm="confirmRiskSave" />
    <StoryEditorDialog ref="storyEditorRef" :in-scope="() => true" />
  </section>
</template>
