<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { projectGeneratorApi } from '@/api/projectGenerator'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'

const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()
const mode = ref('detailed')
const prompt = ref('')
const file = ref(null)
const strategy = ref('merge')
const replaceConfirmed = ref(false)
const run = ref(null)
const busy = ref(false)
const savingStory = ref(false)
const storyDialog = ref(null)
const editingStory = ref(null)
const storyForm = ref({ id: '', title: '', sprint: 1, priority: 'Should', acceptance: '' })
let timer = null
const currentRunKey = 'aiguanli_project_generator_run_id'

const modeText = {
  detailed: { title: '详细自然语言生成', hint: '描述目标、范围、成员分工和交付节奏，由模型生成完整规划草案。' },
  excel: { title: 'Excel / CSV 导入生成', hint: '后端解析 .xlsx 或 .csv，统一映射为 Story、Task、排期和依赖草案。' },
  overview: { title: '概略自然语言智能规划', hint: '输入项目方向，由模型完成需求拆解、任务排期、负责人和依赖规划。' }
}
const activeMode = computed(() => modeText[mode.value])
const draft = computed(() => run.value?.draft)
const canGenerate = computed(() => mode.value === 'excel' ? Boolean(file.value) : prompt.value.trim().length > 0)
const canEditDraft = computed(() => run.value?.status === 'waiting_confirmation' && !session.isViewer)
const sprintOptions = computed(() => (draft.value?.sprints || []).map(item => Number(item.sprint)).filter(Number.isInteger))
const linkedStoryTasks = computed(() => {
  const storyId = editingStory.value?.id
  return storyId ? (draft.value?.tasks || []).filter(task => String(task.story_ref || '').split(',').map(value => value.trim()).includes(storyId)) : []
})
const terminal = computed(() => ['waiting_confirmation', 'completed', 'cancelled', 'failed'].includes(run.value?.status))
const statusText = { queued: '排队中', running: '正在生成并校验', waiting_confirmation: '草案待确认', executing: '正在写入统一项目数据', completed: '已完成', cancelled: '已取消', failed: '生成失败' }
const riskGroups = computed(() => {
  const risks = draft.value?.risks || {}
  return [['loadRisk', '成员负载'], ['dependencyRisk', '任务依赖'], ['sprintRisk', 'Sprint 风险']]
    .map(([key, title]) => ({ key, title, items: risks[key] || [] })).filter(group => group.items.length)
})
const eventSteps = computed(() => (run.value?.events || []).map(event => {
  let detail = {}
  try { detail = JSON.parse(event.detail || '{}') } catch { /* audit detail is optional */ }
  return { kind: event.kind, message: detail.message || event.kind }
}))

function rememberRun(id) { if (id) localStorage.setItem(currentRunKey, id); else localStorage.removeItem(currentRunKey) }
function selectMode(key) { mode.value = key; run.value = null; rememberRun(null); clearTimeout(timer) }
function onFile(event) { file.value = event.target.files?.[0] || null }
async function openStoryEditor(story) {
  if (!canEditDraft.value) return
  editingStory.value = story
  storyForm.value = { id: story.id, title: story.title, sprint: Number(story.sprint), priority: story.priority || 'Should', acceptance: story.acceptance || '' }
  await nextTick()
  storyDialog.value?.showModal()
}
function closeStoryEditor() {
  storyDialog.value?.close()
  editingStory.value = null
}
async function saveStoryDraft() {
  if (!run.value || !editingStory.value || savingStory.value) return
  const form = storyForm.value
  if (!form.title.trim() || !form.acceptance.trim()) { notify('故事名称和验收标准不能为空'); return }
  savingStory.value = true
  try {
    run.value = await projectGeneratorApi.updateStory(run.value.id, editingStory.value.id, {
      title: form.title.trim(), sprint: Number(form.sprint), priority: form.priority, acceptance: form.acceptance.trim()
    })
    closeStoryEditor()
    notify('故事草案已保存并重新校验')
  } catch (error) { notify('草案保存失败：' + error.message) }
  finally { savingStory.value = false }
}
async function refresh(id = run.value?.id) {
  if (!id) return
  run.value = await projectGeneratorApi.get(id)
  mode.value = run.value.mode === 'overview' ? 'overview' : (run.value.mode === 'excel' ? 'excel' : 'detailed')
  strategy.value = run.value.strategy || 'merge'
  rememberRun(run.value.id)
  clearTimeout(timer)
  if (!terminal.value) timer = setTimeout(() => refresh(id), 1500)
}
async function generate() {
  if (!canGenerate.value || busy.value) return
  busy.value = true
  replaceConfirmed.value = false
  try {
    run.value = mode.value === 'excel'
      ? await projectGeneratorApi.importFile(file.value, strategy.value)
      : await projectGeneratorApi.create(mode.value, prompt.value.trim(), strategy.value)
    rememberRun(run.value.id)
    await refresh(run.value.id)
    notify(mode.value === 'excel' ? '表格已解析为项目草案' : '生成请求已提交')
  } catch (error) { notify('生成失败：' + error.message) }
  finally { busy.value = false }
}
async function confirm() {
  if (!run.value || busy.value) return
  if (strategy.value === 'replace' && !replaceConfirmed.value) { notify('请先确认替换现有 Story 和 Task'); return }
  busy.value = true
  try {
    run.value = await projectGeneratorApi.confirm(run.value.id, replaceConfirmed.value)
    await project.loadAll()
    notify('项目规划已写入，四类视图已刷新')
  } catch (error) { await refresh(run.value.id).catch(() => {}); notify('执行失败：' + error.message) }
  finally { busy.value = false }
}
async function cancel() {
  if (!run.value || busy.value) return
  busy.value = true
  try { run.value = await projectGeneratorApi.cancel(run.value.id); notify('已取消，正式项目数据未修改') }
  catch (error) { notify(error.message) }
  finally { busy.value = false }
}
function reset() { clearTimeout(timer); closeStoryEditor(); rememberRun(null); prompt.value = ''; file.value = null; run.value = null; replaceConfirmed.value = false }
onMounted(async () => {
  const savedRunId = localStorage.getItem(currentRunKey)
  if (!savedRunId) return
  try { await refresh(savedRunId) }
  catch { rememberRun(null) }
})
onUnmounted(() => { clearTimeout(timer); storyDialog.value?.close() })
</script>

<template>
  <section class="view project-generator-view" id="view-project-generator">
    <div class="hero">
      <div><div class="eyebrow">PROJECT GENERATOR / 智能协作</div><h1>智能生成项目图</h1><p>从自然语言或表格生成可审阅草案，确认后写入统一数据源并刷新四类视图。</p></div>
      <span class="planning-agent-badge">草案审阅 · 人工确认</span>
    </div>
    <div class="generator-mode-tabs" role="tablist" aria-label="生成模式">
      <button v-for="(item, key) in modeText" :key="key" :class="{ active: mode === key }" role="tab" :aria-selected="mode === key" @click="selectMode(key)">{{ item.title }}</button>
    </div>
    <div class="generator-layout">
      <section class="generator-form">
        <div class="h-sec">{{ activeMode.title }}</div>
        <p class="generator-hint">{{ activeMode.hint }}</p>
        <textarea v-if="mode !== 'excel'" v-model="prompt" rows="9" maxlength="4000" :placeholder="mode === 'detailed' ? '例如：规划一个客户工单系统，包含管理员、客服和主管，分 3 个 Sprint 交付。' : '例如：做一个面向小团队的客户工单系统，6 周完成。'" />
        <template v-else>
          <label class="generator-upload"><input type="file" accept=".csv,.xlsx" @change="onFile">选择 Excel / CSV 文件</label>
          <p class="small">{{ file?.name || '尚未选择文件' }}</p>
          <p class="generator-note">表头可使用 title/name、hours、owner_name 或 owner_id、week_start、week_end、story_ref、depends_on；没有 hours 的行按 Story 导入。普通用户推荐直接填写 owner_name，owner_id 作为兼容方式保留。</p>
        </template>
        <label class="generator-strategy"><span>写入策略</span><select v-model="strategy" :disabled="Boolean(run)"><option value="merge">合并到现有项目</option><option value="replace">替换现有 Story / Task</option></select></label>
        <p v-if="strategy === 'replace'" class="planning-error">替换会在确认时清空现有 Story 和 Task。生成草案阶段不会修改业务数据。</p>
        <div class="generator-actions"><button type="button" @click="reset">清空</button><button type="button" class="primary" :disabled="!canGenerate || busy" @click="generate">{{ busy ? '处理中…' : '生成项目图草案' }}</button></div>
      </section>
      <aside class="generator-output">
        <div class="h-sec">统一输出</div>
        <div class="generator-graph-list"><div><b>▦ 用户故事地图</b><span>Story / Sprint / Activity</span></div><div><b>▤ 甘特图</b><span>Task / Week / Dependency</span></div><div><b>▥ 成员任务图</b><span>Owner / Hours / Capacity</span></div><div><b>◇ UML</b><span>由 Story / Member 派生用例内容</span></div></div>
        <div v-if="!run" class="empty generator-empty">选择生成方式。正式 Story 和 Task 只会在人工确认后变更。</div>
      </aside>
    </div>
    <section v-if="run" class="generator-draft-section">
      <div class="generator-result">
        <div class="generator-result-head"><b>{{ statusText[run.status] || run.status }}</b><span class="small">{{ run.source_name }}</span></div>
        <div v-if="eventSteps.length" class="planning-steps"><span v-for="(event, index) in eventSteps" :key="index" :class="{ failed: event.kind === 'failed' }">{{ event.message }}</span></div>
        <p v-if="run.error_message" class="planning-error">{{ run.error_message }}</p>
        <template v-if="draft">
          <div class="generator-stats"><strong>{{ draft.story_count }}<small>故事</small></strong><strong>{{ draft.task_count }}<small>任务</small></strong><strong>{{ draft.dependencies?.length || 0 }}<small>依赖</small></strong></div>
          <div class="generator-draft-list">
            <div class="generator-draft-heading"><b>故事草案</b><span v-if="canEditDraft" class="small">确认前可修改</span></div>
            <ul class="generator-story-list">
              <li v-for="story in draft.stories" :key="story.id">
                <div class="generator-story-summary" :title="story.title"><span class="mono">{{ story.id }}</span><span class="generator-story-title">{{ story.title }}</span><span class="generator-story-meta">S{{ story.sprint }} · {{ story.priority }}</span></div>
                <button v-if="canEditDraft" type="button" class="generator-story-edit" :aria-label="'编辑 ' + story.id" @click="openStoryEditor(story)">编辑</button>
              </li>
            </ul>
          </div>
          <div v-if="riskGroups.length || draft.warnings?.length" class="generator-review-grid">
            <div v-if="riskGroups.length" class="planning-impact-report"><b>风险分析</b><section v-for="group in riskGroups" :key="group.key"><strong>{{ group.title }}</strong><div v-for="(item, index) in group.items" :key="index">{{ item.message }}</div></section></div>
            <div v-if="draft.warnings?.length" class="planning-warnings"><b>校验提示</b><div v-for="warning in draft.warnings" :key="warning">{{ warning }}</div></div>
          </div>
          <div v-if="run.status === 'waiting_confirmation'" class="generator-confirm-bar">
            <label v-if="strategy === 'replace'" class="generator-replace-confirm"><input v-model="replaceConfirmed" type="checkbox"> 我确认替换当前全部 Story 和 Task</label>
            <div v-if="!session.isViewer" class="planning-actions"><button type="button" :disabled="busy" @click="cancel">取消生成</button><button type="button" class="primary" :disabled="busy || (strategy === 'replace' && !replaceConfirmed)" @click="confirm">确认写入项目</button></div>
            <p v-else class="small">只读角色可以审阅草案，但不能写入项目。</p>
          </div>
        </template>
      </div>
    </section>
    <div class="generator-source-note"><b>智能体边界</b><span>会议智能体处理会议内容；画图智能体修改已有规划；智能生成项目图从零创建规划。</span><span>当前数据：{{ project.stories.length }} Story · {{ project.tasks.length }} Task · {{ project.members.length }} 成员</span></div>
    <dialog ref="storyDialog" class="generator-story-dialog" aria-labelledby="generator-story-dialog-title" @cancel.prevent="closeStoryEditor">
      <header><h2 id="generator-story-dialog-title">编辑用户故事草案</h2><button type="button" aria-label="关闭" @click="closeStoryEditor">×</button></header>
      <form class="generator-story-form" @submit.prevent="saveStoryDraft">
        <label class="field"><span>Story ID</span><input :value="storyForm.id" readonly></label>
        <label class="field"><span>Story 名称</span><input v-model="storyForm.title" maxlength="200" required></label>
        <div class="generator-story-fields">
          <label class="field"><span>Sprint</span><select v-model.number="storyForm.sprint"><option v-for="sprint in sprintOptions" :key="sprint" :value="sprint">Sprint {{ sprint }}</option></select></label>
          <label class="field"><span>MoSCoW 优先级</span><select v-model="storyForm.priority"><option value="Must">Must</option><option value="Should">Should</option><option value="Could">Could</option></select></label>
        </div>
        <label class="field"><span>验收标准</span><textarea v-model="storyForm.acceptance" rows="6" required placeholder="输入验收标准；多条内容可分行填写"></textarea></label>
        <p class="generator-linked-tasks"><b>关联任务</b><span v-if="linkedStoryTasks.length">当前有 {{ linkedStoryTasks.length }} 个任务：{{ linkedStoryTasks.map(task => task.id).join('、') }}。修改 Sprint 后会重新检查 Story / Task 排期冲突。</span><span v-else>当前没有任务关联此 Story。</span></p>
        <div class="dialog-actions"><button type="button" :disabled="savingStory" @click="closeStoryEditor">取消</button><button type="submit" class="primary" :disabled="savingStory">{{ savingStory ? '保存中…' : '保存修改' }}</button></div>
      </form>
    </dialog>
  </section>
</template>
