<script setup>
import { computed, ref } from 'vue'
import { useProjectStore, taskRefs } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useAgentRefresh, completingTaskId } from '@/composables/useAgentRefresh'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import { MILESTONES } from '@/data/seed'
import { memberLabel } from '@/data/memberIdentity'
import GanttRow from '@/components/gantt/GanttRow.vue'
import TaskEditorDialog from '@/components/gantt/TaskEditorDialog.vue'
import StoryEditorDialog from '@/components/board/StoryEditorDialog.vue'

/* 甘特图(融合方案)
   - 保留 v2 血缘:父行=看板卡(起止=子任务周并集、工时加权进度),子行缩进,管理任务虚线,独立任务区
   - 引入 legacy 新版内容:Sprint 分带表头、行内任务细节(负责人/工时/Sprint/关联 Story/看板卡)、
     可点击任务条(选中高亮 + 前置/后续任务高亮)、任务详情面板、任务编辑弹窗(真实 PATCH /api/tasks/{id})
   - 交互按 Vue 重写:选中状态由 selection 持有,父条=故事卡(编辑走故事弹窗),子条=任务(编辑走任务弹窗) */
const project = useProjectStore()
const session = useSessionStore()
const sprintLabel = ['', 'S1', 'S1', 'S2', 'S2', 'S3', 'S3']
const sprintOf = w => Math.ceil(w / 2)
/* 只读账号(viewer)的编辑入口保持可见但禁用,title 说明原因 */
const viewerTitle = computed(() => (session.isViewer ? READONLY_TITLE : ''))
/* 在当前(选中)任务上标记完成 → 触发任务提交智能体,见 useAgentRefresh */
const { completeTask } = useAgentRefresh()
const doneTitle = computed(() => (session.isViewer ? READONLY_TITLE : '标记完成并触发任务提交智能体'))

const selection = ref(null)          // { type:'task'|'story', id }
const taskEditorRef = ref(null)
const storyEditorRef = ref(null)

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
  const span = t.w[1] - t.w[0] + 1
  const cross = sprintOf(t.w[0]) !== sprintOf(t.w[1])
  const relation = relationOf(t.id)
  const cls = [`o${t.owner}`]
  if (t.type === 'management') cls.push('mgmt')
  else if (t.status === 2 || t.progress >= 100) cls.push('task-done')
  if (relation) cls.push(relation)
  return {
    left: ((t.w[0] - 1) / 6 * 100).toFixed(2),
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
  return [
    { text: t.id, cls: 'taskid' },
    { text: '· ' + project.memberName(t.owner), cls: 'ownerref' },
    { text: '· ' + t.h + 'h' },
    { text: '· ' + project.taskSprintText(t) },
    { text: '· ' + (t.story || '未关联 Story'), cls: 'storyref', title: t.story || '未关联 Story' },
    { text: '· ' + (t.card || '管理任务') },
    { text: '· ' + project.taskStatusText(t) }
  ]
}

/* 开发任务按看板卡分组:父行(卡+加权进度) + 子行;管理任务独立虚线;独立任务解绑可见 */
const groups = computed(() => {
  const g = {}
  project.tasks.filter(t => t.type !== 'management' && t.card).forEach(t => { (g[t.card] ??= []).push(t) })
  return g
})
const mgmts = computed(() => project.tasks.filter(t => t.type === 'management'))
const orphans = computed(() => project.tasks.filter(t => t.type !== 'management' && !t.card))

const groupRows = computed(() => Object.entries(groups.value).map(([cardId, subs]) => {
  const s = project.stories.find(x => x.id === cardId)
  const w0 = Math.min(...subs.map(t => t.w[0])), w1 = Math.max(...subs.map(t => t.w[1]))
  const pct = Math.round(project.cardPct(s || { status: 0, id: cardId }) * 100)
  const eh = subs.reduce((a, t) => a + (t.eh || t.h), 0)
  return {
    cardId,
    parent: {
      title: cardId + ' ' + (s ? s.title : ''),
      sub: `父卡 · ${subs.length} 子任务 · ${eh}h · 加权 ${pct}%`,
      bar: {
        left: ((w0 - 1) / 6 * 100).toFixed(2),
        width: ((w1 - w0 + 1) / 6 * 100).toFixed(2),
        title: `看板卡 ${cardId} · ${s ? s.title : ''} · ${subs.length} 条子任务 · ${eh}h · 工时加权进度 ${pct}%（点击查看该卡血缘）`,
        cls: 'parent',
        label: `${cardId} · ${pct}%`,
        pct,
        key: `story:${cardId}`,
        selected: selectedStoryId.value === cardId,
        relation: ''
      }
    },
    children: subs.map(t => ({ task: t, title: t.name, meta: metaOf(t), bar: barOf(t, `task:${t.id}`) }))
  }
}))
const orphanChildren = computed(() => orphans.value.map(t => ({
  task: t, title: t.name, meta: metaOf(t), bar: barOf(t, `task:${t.id}`)
})))

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
</script>

<template>
  <section class="view" id="view-gantt">
    <div class="hero">
      <div>
        <div class="eyebrow">GANTT CHART / 6 周排期 · 强制血缘</div>
        <h1>甘特图</h1>
        <p>开发任务强制挂载看板卡 · 管理任务独立虚线。</p>
      </div>
      <span class="demo">Sprint 1 = W1–W2 · Sprint 2 = W3–W4 · Sprint 3 = W5–W6</span>
    </div>
    <div class="gantt-wrap">
      <div class="gantt" id="gantt">
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
        <template v-for="g in groupRows" :key="g.cardId">
          <GanttRow variant="parent" :title="g.parent.title" :sub="g.parent.sub" :bar="g.parent.bar" @select="select" />
          <GanttRow v-for="c in g.children" :key="c.task.id" variant="child" :title="c.title" :meta="c.meta" :bar="c.bar" @select="select" />
        </template>
        <GanttRow v-for="t in mgmts" :key="'mgmt-' + t.id" variant="mgmt" :title="t.name" :sub="`${t.id} · 管理 · ${t.h}h`" :meta="metaOf(t)" :bar="barOf(t, `task:${t.id}`)" @select="select" />
        <template v-if="orphans.length">
          <GanttRow variant="group" title="独立任务" :sub="`已解绑/未挂卡的开发任务 · ${orphans.length} 条`" :bar="null" />
          <GanttRow v-for="c in orphanChildren" :key="'orphan-' + c.task.id" variant="child" :title="c.title" :meta="c.meta" :bar="c.bar" @select="select" />
        </template>
        <div class="mile-row">
          <div class="mile-title">◆ 项目里程碑</div>
          <div class="mile-track">
            <div v-for="m in MILESTONES" :key="m.id" class="milestone" :title="m.desc" :style="{ left: ((m.week - 0.5) / 6 * 100).toFixed(2) + '%' }">
              <span class="diamond"></span><span>{{ m.id }} {{ m.name }}</span>
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
      <span class="chip note">子任务条 = 计划起止范围（不按工时比例）</span>
      <span class="chip note">父卡条 = 子任务周并集，填充 = 工时加权进度</span>
      <span class="chip note">点击任务条查看前置 / 后续任务</span>
    </div>

    <!-- 详情面板:选中任务 → 任务详情 + 编辑任务;选中父卡 → 故事血缘 + 编辑故事 -->
    <div class="gantt-detail" :class="{ empty: !selection }" id="gantt-detail">
      <template v-if="!selection">
        点击任务条可查看关联故事、前置任务与后续任务；点击父卡条可查看该看板卡的子任务血缘。
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
    <StoryEditorDialog ref="storyEditorRef" :in-scope="() => true" />
  </section>
</template>
