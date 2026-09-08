<script setup>
import { ref, computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { storiesApi } from '@/api/stories'
import { statuses, activities, STORIES_KEY, LOG_KEY } from '@/constants'
import { SEED } from '@/data/seed'
import BoardSummary from '@/components/board/BoardSummary.vue'
import BoardToolbar from '@/components/board/BoardToolbar.vue'
import StoryCard from '@/components/board/StoryCard.vue'
import StoryMapGrid from '@/components/board/StoryMapGrid.vue'
import ChangeLogPanel from '@/components/board/ChangeLogPanel.vue'
import StoryEditorDialog from '@/components/board/StoryEditorDialog.vue'

/* 看板主视图:结构对齐旧版 L490-545,逻辑对齐 selected()(L910-913)/
   render()(L919-933)/拖拽换状态(L964-984)/导出 dl(L985-991)/恢复演示(L1435-1440) */
const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()

const boardView = ref('board')
const search = ref('')
const sprint = ref('1')
const owner = ref('all')
const editorRef = ref(null)

/* 当前筛选范围:搜索/迭代/负责人 三条件合并(对齐旧版 selected) */
const data = computed(() => {
  const q = search.value.trim().toLowerCase()
  return project.stories.filter(s =>
    (sprint.value === 'all' || s.sprint === +sprint.value) &&
    (owner.value === 'all' || s.owner === +owner.value) &&
    (!q || [s.id, s.title, s.description, s.acceptance, (s.owner == null ? '未分配' : '成员' + (s.owner + 1)), activities[s.activity - 1]].join(' ').toLowerCase().includes(q))
  )
})
const inScope = id => data.value.some(s => s.id === id)

function openEditor(id = null, status = 0) {
  editorRef.value?.open(id, status, { owner: owner.value, sprint: sprint.value })
}

/* ==================== 拖拽换状态 ==================== */
const dragOverCol = ref(-1)
function onDrop(i) {
  dragOverCol.value = -1
  const id = dragId
  dragId = ''
  const s = project.stories.find(s => s.id === id)
  if (!s || s.status === i) return
  if (guard('拖拽修改状态')) return
  if (session.apiMode) {
    storiesApi.patch(id, { status: i })
      .then(async () => { await project.loadAll(); notify(id + ' → ' + statuses[i] + ' · 已同步到服务器') })
      .catch(err => notify(err.message))
  } else {
    s.status = i
    project.addlog('move', s.id, s.title + ' → ' + statuses[s.status])
    project.persist()
    notify(`${s.id} → ${statuses[s.status]}${project.storageOK ? '' : ' · 暂未保存到本机'}`)
  }
}
let dragId = ''
function onCardDragStart(id, e) {
  dragId = id
  e.dataTransfer.setData('text/plain', id)
  e.dataTransfer.effectAllowed = 'move'
}

/* ==================== 导出 / 恢复演示 ==================== */
function dl(name, blob) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
function exportJson() {
  dl('爱管理-用户故事-' + new Date().toISOString().slice(0, 10) + '.json', new Blob([JSON.stringify(project.stories, null, 2)], { type: 'application/json' }))
  notify('已导出 JSON 备份')
}
function exportCsv() {
  const head = ['ID', '标题', '用户故事', 'MoSCoW', 'Sprint', '骨干活动', '负责人', '状态', '验收条件']
  const rows = project.stories.map(s =>
    [s.id, s.title, s.description, s.priority, 'S' + s.sprint, 'A' + s.activity + ' ' + activities[s.activity - 1],
     (s.owner == null ? '未分配' : '成员' + (s.owner + 1)), statuses[s.status], s.acceptance]
      .map(v => '"' + String(v).replace(/"/g, '""') + '"').join(','))
  dl('爱管理-用户故事.csv', new Blob(['\ufeff' + head.join(',') + '\n' + rows.join('\n')], { type: 'text/csv;charset=utf-8' }))
  notify('已导出 CSV（可用 Excel 打开）')
}
async function resetDemo() {
  if (session.apiMode) {
    if (!confirm('在线模式下将重新拉取服务器数据(不会重置服务器)。继续？')) return
    try { await project.loadAll(); notify('已重新拉取服务器数据') } catch (e) { notify(e.message) }
    return
  }
  if (!confirm('确定恢复为演示数据？当前所有修改将丢失。')) return
  project.stories = structuredClone(SEED)
  project.log = []
  try {
    localStorage.setItem(STORIES_KEY, JSON.stringify(project.stories))
    localStorage.setItem(LOG_KEY, '[]')
  } catch { /* ignore */ }
  project.persist()
  notify('已恢复演示数据（M01–M23）')
}
</script>

<template>
  <section class="view" id="view-board">
    <div class="hero">
      <div>
        <div class="eyebrow">QUEST BOARD / 任务集结处</div>
        <h1>用户故事看板</h1>
        <p>把每一个想法，推进到完成。</p>
      </div>
      <button class="primary" id="new" @click="openEditor()">＋ 新建故事</button>
    </div>

    <BoardSummary :data="data" :total-stories="project.stories.length" />
    <BoardToolbar v-model:board-view="boardView" v-model:search="search" v-model:sprint="sprint" v-model:owner="owner" />

    <div id="boardpanel" role="tabpanel" aria-labelledby="boardtab" :hidden="boardView !== 'board'">
      <div class="board" id="board">
        <section
          v-for="(name, i) in statuses" :key="name"
          class="column" :class="{ dragover: dragOverCol === i }"
          :data-status="i" :aria-label="name"
          @dragover.prevent="dragOverCol = i"
          @dragleave="dragOverCol = -1"
          @drop.prevent="onDrop(i)"
        >
          <div class="colhead"><span>{{ ['□', '▣', '■'][i] }} {{ name }}</span><span class="count">{{ data.filter(s => s.status === i).length }}</span></div>
          <div class="colnote">{{ ['准备好后，就开始吧', '专注当前，逐一推进', '每一步完成，都算数'][i] }}</div>
          <div class="cards">
            <template v-for="s in data.filter(s => s.status === i)" :key="s.id">
              <StoryCard :story="s" @open="openEditor($event)" @dragstart="onCardDragStart(s.id, $event)" />
            </template>
            <div v-if="!data.filter(s => s.status === i).length" class="empty">此列暂无故事</div>
          </div>
          <button class="addcol" :data-new="i" @click="openEditor(null, i)">＋ 添加故事</button>
        </section>
      </div>
    </div>
    <div id="mappanel" role="tabpanel" aria-labelledby="maptab" :hidden="boardView !== 'map'">
      <StoryMapGrid :data="data" :sprint="sprint" @open="openEditor($event)" />
    </div>

    <div class="footer">
      <span>■ Must 必须做　■ Should 应该做　■ Could 可以做</span>
      <span id="savehint">{{ project.savehint }}</span>
      <span class="hintcard" id="hintcount">已同步 {{ project.stories.length }} 条故事（{{ project.stories[0] ? project.stories[0].id : 'M01' }}–{{ project.stories[project.stories.length - 1] ? project.stories[project.stories.length - 1].id : '' }}）</span>
      <span style="display:flex;gap:8px;flex-wrap:wrap">
        <button id="expjson" @click="exportJson">导出 JSON</button>
        <button id="expcsv" @click="exportCsv">导出 CSV</button>
        <button id="reset" class="danger" @click="resetDemo">恢复演示</button>
      </span>
    </div>

    <ChangeLogPanel />
    <StoryEditorDialog ref="editorRef" :in-scope="inScope" />
  </section>
</template>
