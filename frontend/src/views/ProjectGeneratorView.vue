<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectGeneratorApi } from '@/api/projectGenerator'
import { useProjectStore } from '@/stores/project'
import { useProjectsStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'

const project = useProjectStore()
const projects = useProjectsStore()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const { notify } = useToast()
const mode = ref('detailed')
const createMode = computed(() => route.meta.generatorContext === 'create')
const projectForm = ref({ name: '', description: '', total_sprints: 3 })
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
let refreshSequence = 0
const activeRunId = ref(null)
const currentRunKey = computed(() => createMode.value ? 'aiguanli_project_generator_run_id-new' : `aiguanli_project_generator_run_id-${projects.currentProjectId || 'none'}`)

const modeText = {
  detailed: { title: '详细自然语言生成', hint: '描述目标、范围、成员分工和交付节奏，由模型生成完整规划草案。' },
  excel: { title: 'Excel / CSV 导入生成', hint: '后端解析 .xlsx 或 .csv，统一映射为 Story、Task、排期和依赖草案。' },
  overview: { title: '概略自然语言智能规划', hint: '输入项目方向，由模型完成需求拆解、任务排期、负责人和依赖规划。' }
}
const activeMode = computed(() => modeText[mode.value])
const draft = computed(() => run.value?.draft)
const canGenerate = computed(() => (!createMode.value || projectForm.value.name.trim()) && (mode.value === 'excel' ? Boolean(file.value) : prompt.value.trim().length > 0))
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

function rememberRun(id) { if (id) localStorage.setItem(currentRunKey.value, id); else localStorage.removeItem(currentRunKey.value) }
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
    const changes = {
      title: form.title.trim(), sprint: Number(form.sprint), priority: form.priority, acceptance: form.acceptance.trim()
    }
    run.value = createMode.value
      ? await projectGeneratorApi.updateStoryNew(run.value.id, editingStory.value.id, changes)
      : await projectGeneratorApi.updateStory(run.value.id, editingStory.value.id, changes, projects.currentProjectId)
    closeStoryEditor()
    notify('故事草案已保存并重新校验')
  } catch (error) { notify('草案保存失败：' + error.message) }
  finally { savingStory.value = false }
}
async function refresh(id = run.value?.id) {
  if (!id) return
  const sequence = ++refreshSequence
  const requestedId = id
  const nextRun = createMode.value ? await projectGeneratorApi.getNew(requestedId) : await projectGeneratorApi.get(requestedId, projects.currentProjectId)
  if (sequence !== refreshSequence || activeRunId.value !== requestedId) return
  run.value = nextRun
  if (run.value.status === 'failed') run.value = { ...run.value, draft: null }
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
  clearTimeout(timer)
  refreshSequence++
  activeRunId.value = null
  run.value = null
  rememberRun(null)
  try {
    run.value = createMode.value
      ? (mode.value === 'excel'
          ? await projectGeneratorApi.importNew(file.value, projectForm.value)
          : await projectGeneratorApi.createNew(mode.value, prompt.value.trim(), projectForm.value))
      : (mode.value === 'excel'
          ? await projectGeneratorApi.importFile(file.value, strategy.value, projects.currentProjectId)
          : await projectGeneratorApi.create(mode.value, prompt.value.trim(), strategy.value, projects.currentProjectId))
    activeRunId.value = run.value.id
    rememberRun(run.value.id)
    await refresh(run.value.id)
    notify(mode.value === 'excel' ? '表格已解析为项目草案' : '生成请求已提交')
  } catch (error) { clearTimeout(timer); refreshSequence++; activeRunId.value = null; run.value = null; rememberRun(null); notify('生成失败：' + error.message) }
  finally { busy.value = false }
}
async function confirm() {
  if (!run.value || busy.value) return
  if (strategy.value === 'replace' && !replaceConfirmed.value) { notify('请先确认替换现有 Story 和 Task'); return }
  busy.value = true
  try {
    run.value = createMode.value
      ? await projectGeneratorApi.confirmNew(run.value.id)
      : await projectGeneratorApi.confirm(run.value.id, replaceConfirmed.value, projects.currentProjectId)
    if (createMode.value) {
      const projectId = run.value.project_id
      if (!projectId) throw new Error('后端未返回新项目编号')
      await projects.loadProjects()
      await projects.selectProject(projectId)
      await project.loadAll(projectId)
      rememberRun(null)
      notify(`${projectId} 新项目已创建`)
      await router.push({ name: 'project-overview', params: { projectId } })
    } else {
      await project.loadAll(projects.currentProjectId)
      notify('项目规划已写入，四类视图已刷新')
    }
  } catch (error) { await refresh(run.value.id).catch(() => {}); notify('执行失败：' + error.message) }
  finally { busy.value = false }
}
async function cancel() {
  if (!run.value || busy.value) return
  busy.value = true
  try { run.value = createMode.value ? await projectGeneratorApi.cancelNew(run.value.id) : await projectGeneratorApi.cancel(run.value.id, projects.currentProjectId); notify('已取消，正式项目数据未修改') }
  catch (error) { notify(error.message) }
  finally { busy.value = false }
}
function reset() { clearTimeout(timer); closeStoryEditor(); rememberRun(null); prompt.value = ''; file.value = null; run.value = null; replaceConfirmed.value = false }
onMounted(async () => {
  const savedRunId = localStorage.getItem(currentRunKey.value)
  if (!savedRunId) return
  try { activeRunId.value = savedRunId; await refresh(savedRunId) }
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
        <div v-if="createMode && mode === 'detailed'" class="generator-detailed-form">
          <section class="generator-form-block generator-basics" aria-labelledby="generator-basics-title">
            <div class="generator-block-heading">
              <h2 id="generator-basics-title">项目基础信息</h2>
              <span>定义项目名称、范围和迭代周期</span>
            </div>
            <div class="generator-basic-fields">
              <label class="generator-field generator-name-field"><span>项目名称</span><input v-model="projectForm.name" maxlength="200" required placeholder="例如：客户工单系统"></label>
              <label class="generator-field generator-description-field"><span>项目描述</span><textarea v-model="projectForm.description" rows="3" maxlength="1000" placeholder="描述项目目标和范围"></textarea></label>
              <label class="generator-field generator-sprint-field"><span>Sprint 数量</span><input v-model.number="projectForm.total_sprints" type="number" min="1" max="6"></label>
            </div>
          </section>
          <section class="generator-form-block generator-requirements generator-detailed-requirements" aria-labelledby="generator-requirements-title">
            <div class="generator-block-heading">
              <h2 id="generator-requirements-title">详细自然语言需求</h2>
              <span>描述项目目标、主要功能、成员分工和交付节奏，模型将据此生成完整规划草案。</span>
            </div>
            <textarea v-model="prompt" rows="7" maxlength="4000" placeholder="例如：规划一个客户工单系统，包含管理员、客服和主管，分 3 个 Sprint 交付。"></textarea>
          </section>
        </div>
        <div v-else-if="createMode && mode === 'excel'" class="generator-detailed-form generator-excel-form">
          <section class="generator-form-block generator-basics" aria-labelledby="generator-excel-basics-title">
            <div class="generator-block-heading">
              <h2 id="generator-excel-basics-title">项目基础信息</h2>
              <span>定义项目名称、范围和迭代周期</span>
            </div>
            <div class="generator-basic-fields">
              <label class="generator-field generator-name-field"><span>项目名称</span><input v-model="projectForm.name" maxlength="200" required placeholder="例如：客户工单系统"></label>
              <label class="generator-field generator-description-field"><span>项目描述</span><textarea v-model="projectForm.description" rows="3" maxlength="1000" placeholder="描述项目目标和范围"></textarea></label>
              <label class="generator-field generator-sprint-field"><span>Sprint 数量</span><input v-model.number="projectForm.total_sprints" type="number" min="1" max="6"></label>
            </div>
          </section>
          <section class="generator-form-block generator-import" aria-labelledby="generator-import-title">
            <div class="generator-block-heading">
              <h2 id="generator-import-title">导入 Excel / CSV 文件</h2>
              <span>上传包含 Story、Task、排期等信息的 .xlsx 或 .csv 文件。</span>
            </div>
            <label class="generator-file-box">
              <input class="generator-file-input" type="file" accept=".csv,.xlsx" @change="onFile">
              <span class="generator-file-action">选择文件</span>
              <span class="generator-file-status" :class="{ 'has-file': file }" :title="file?.name || '尚未选择文件'">{{ file?.name || '尚未选择文件' }}</span>
              <small>支持 .xlsx / .csv</small>
            </label>
          </section>
        </div>
        <div v-else-if="createMode && mode === 'overview'" class="generator-detailed-form generator-overview-form">
          <section class="generator-form-block generator-basics" aria-labelledby="generator-overview-basics-title">
            <div class="generator-block-heading">
              <h2 id="generator-overview-basics-title">项目基础信息</h2>
              <span>定义项目名称、范围和迭代周期</span>
            </div>
            <div class="generator-basic-fields">
              <label class="generator-field generator-name-field"><span>项目名称</span><input v-model="projectForm.name" maxlength="200" required placeholder="例如：客户工单系统"></label>
              <label class="generator-field generator-description-field"><span>项目描述</span><textarea v-model="projectForm.description" rows="3" maxlength="1000" placeholder="描述项目目标和范围"></textarea></label>
              <label class="generator-field generator-sprint-field"><span>Sprint 数量</span><input v-model.number="projectForm.total_sprints" type="number" min="1" max="6"></label>
            </div>
          </section>
          <section class="generator-form-block generator-requirements generator-overview-requirements" aria-labelledby="generator-overview-requirements-title">
            <div class="generator-block-heading">
              <h2 id="generator-overview-requirements-title">概略自然语言需求</h2>
              <span>描述项目方向、核心功能和交付目标，模型将自动补全 Story、Task、负责人、依赖和里程碑。</span>
            </div>
            <textarea v-model="prompt" rows="6" maxlength="4000" placeholder="例如：做一个面向小团队的客户工单系统，6 周完成。"></textarea>
          </section>
          <p class="generator-note generator-overview-note">新建模式只会生成新项目草案，确认后分配新的 Project ID，不会合并或替换现有项目。</p>
        </div>
        <template v-else>
          <p class="generator-hint">{{ activeMode.hint }}</p>
          <textarea v-if="mode !== 'excel'" v-model="prompt" rows="9" maxlength="4000" :placeholder="mode === 'detailed' ? '例如：规划一个客户工单系统，包含管理员、客服和主管，分 3 个 Sprint 交付。' : '例如：做一个面向小团队的客户工单系统，6 周完成。'" />
        </template>
        <template v-if="mode === 'excel' && !createMode">
          <label class="generator-file-box">
            <input class="generator-file-input" type="file" accept=".csv,.xlsx" @change="onFile">
            <span class="generator-file-action">选择文件</span>
            <span class="generator-file-status" :class="{ 'has-file': file }" :title="file?.name || '尚未选择文件'">{{ file?.name || '尚未选择文件' }}</span>
            <small>支持 .xlsx / .csv</small>
          </label>
        </template>
        <label v-if="!createMode" class="generator-strategy"><span>写入策略</span><select v-model="strategy" :disabled="Boolean(run)"><option value="merge">合并到现有项目</option><option value="replace">替换现有 Story / Task</option></select></label>
        <p v-if="!createMode && strategy === 'replace'" class="planning-error">替换会在确认时清空现有 Story 和 Task。生成草案阶段不会修改业务数据。</p>
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
            <label v-if="!createMode && strategy === 'replace'" class="generator-replace-confirm"><input v-model="replaceConfirmed" type="checkbox"> 我确认替换当前全部 Story 和 Task</label>
            <div v-if="!session.isViewer" class="planning-actions"><button type="button" :disabled="busy" @click="cancel">取消生成</button><button type="button" class="primary" :disabled="busy || (!createMode && strategy === 'replace' && !replaceConfirmed)" @click="confirm">{{ createMode ? '确认创建新项目' : '确认写入项目' }}</button></div>
            <p v-else class="small">只读角色可以审阅草案，但不能写入项目。</p>
          </div>
        </template>
      </div>
    </section>
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

<style scoped>
.project-generator-view > .hero {
  margin-bottom: 22px;
}

.generator-mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  margin: 0 0 20px;
  padding: 0;
  border: 0;
  background: transparent;
}

.generator-mode-tabs button {
  min-width: 0;
  min-height: 52px;
  padding: 10px 14px;
  border: 2px solid var(--ink);
  background: var(--paper);
  box-shadow: none;
  color: var(--ink);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0;
  text-align: center;
}

.generator-mode-tabs button:last-child {
  border-right: 2px solid var(--ink);
}

.generator-mode-tabs button:hover {
  background: var(--paper-2);
}

.generator-mode-tabs button.active {
  border-color: var(--ink);
  background: var(--green);
  box-shadow: inset 0 -4px 0 var(--ink);
  font-weight: 800;
}

.generator-mode-tabs button:focus-visible {
  outline: 3px solid #c1662a;
  outline-offset: 2px;
}

.generator-form,
.generator-output {
  padding: 18px;
}

.generator-form > .h-sec,
.generator-output > .h-sec {
  margin: 2px 0 16px;
}

.generator-detailed-form {
  display: grid;
  gap: 18px;
}

.generator-form-block {
  min-width: 0;
}

.generator-form-block + .generator-form-block {
  padding-top: 18px;
  border-top: 2px solid var(--line);
}

.generator-block-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.generator-block-heading h2 {
  flex: 0 0 auto;
  margin: 0;
  color: var(--ink);
  font: 700 14px/1.3 var(--mono);
  letter-spacing: 1px;
}

.generator-block-heading span {
  min-width: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
  text-align: right;
}

.generator-basic-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}

.generator-field {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.generator-field > span {
  font-size: 12px;
  font-weight: 700;
  line-height: 1.3;
}

.generator-field input,
.generator-field textarea {
  width: 100%;
  min-width: 0;
}

.generator-name-field {
  max-width: 560px;
}

.generator-description-field textarea {
  min-height: 96px;
}

.generator-sprint-field {
  width: 132px;
}

.generator-sprint-field input {
  height: 40px;
}

.generator-requirements > textarea {
  min-height: 238px;
  padding: 12px 14px;
  line-height: 1.65;
}

.generator-detailed-requirements > textarea {
  min-height: 360px;
  padding: 12px 14px;
  overflow: auto;
  resize: vertical;
  font-size: 14px;
  line-height: 1.55;
}

.generator-overview-requirements > textarea {
  min-height: 196px;
}

.generator-overview-note {
  margin: 0;
}

.generator-file-box {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px 12px;
  min-height: 116px;
  padding: 16px;
  border: 2px dashed var(--ink);
  background: var(--paper-2);
  cursor: pointer;
}

.generator-file-box:focus-within {
  outline: 3px solid #c1662a;
  outline-offset: 3px;
}

.generator-file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.generator-file-action {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  padding: 6px 14px;
  border: 2px solid var(--ink);
  background: var(--green);
  box-shadow: 2px 2px 0 var(--ink);
  font-weight: 700;
  white-space: nowrap;
}

.generator-file-box:hover .generator-file-action {
  background: var(--green-2);
}

.generator-file-status {
  min-width: 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.generator-file-status.has-file {
  color: var(--ink);
  font-weight: 700;
}

.generator-file-box small {
  grid-column: 1 / -1;
  color: var(--muted);
  font: 11px/1.4 var(--mono);
}

.generator-actions {
  align-items: stretch;
  padding-top: 2px;
}

.generator-actions button {
  min-width: 104px;
  min-height: 40px;
  padding-top: 7px;
  padding-bottom: 7px;
}

.generator-actions .primary {
  min-width: 190px;
}

.generator-output .generator-graph-list {
  gap: 10px;
}

.generator-output .generator-graph-list > div {
  padding: 11px 12px;
}

.generator-output .generator-empty {
  margin-top: 10px;
}

@media (max-width: 800px) {
  .generator-mode-tabs {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .generator-mode-tabs button,
  .generator-mode-tabs button:last-child {
    border: 2px solid var(--ink);
  }

  .generator-form,
  .generator-output {
    padding: 14px;
  }

  .generator-block-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .generator-block-heading span {
    text-align: left;
  }

  .generator-name-field {
    max-width: none;
  }

  .generator-file-box {
    grid-template-columns: 1fr;
  }

  .generator-file-status,
  .generator-file-box small {
    grid-column: 1;
  }
}

@media (max-width: 480px) {
  .generator-actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .generator-actions button,
  .generator-actions .primary {
    width: 100%;
    min-width: 0;
  }
}
</style>
