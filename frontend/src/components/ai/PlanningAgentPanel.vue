<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { agentApi } from '@/api/agent'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'

const project = useProjectStore()
const { notify } = useToast()
const session = useSessionStore()
const request = ref('')
const run = ref(null)
const busy = ref(false)
let timer = null
const result = computed(() => run.value?.result)
const statusText = { queued: '排队中', running: '正在读取项目数据并分析', waiting_confirmation: '待确认执行', executing: '正在执行', completed: '已完成', cancelled: '已取消', failed: '分析失败' }
const eventSteps = computed(() => {
  const events = run.value?.events || []
  const labels = { queued: '画图请求已入队', running: '开始读取项目数据并分析', tool_call: '查询真实项目数据', plan_generated: '已生成结构化修改方案', impact_analyzed: '已完成影响分析', waiting_confirmation: '等待人工确认', confirmed: '用户已确认执行', action_started: '开始执行修改', action_completed: '修改动作已完成', completed: '执行完成', cancelled: '已取消，未修改业务数据', failed: '画图分析失败' }
  return events.map(event => {
    let detail = {}
    try { detail = JSON.parse(event.detail || '{}') } catch { /* ignore malformed audit detail */ }
    return { kind: event.kind, label: labels[event.kind] || event.kind, message: detail.message || '' }
  })
})
const actionCount = computed(() => result.value?.actions?.length || 0)
const impactGroups = computed(() => {
  const report = result.value?.impact_analysis || {}
  return [
    ['loadRisk', '成员负载'],
    ['dependencyRisk', '任务依赖'],
    ['sprintRisk', 'Sprint 冲突'],
    ['delayRisk', '延期风险'],
    ['futureTaskImpact', '后续任务影响']
  ].map(([key, title]) => ({ key, title, items: report[key] || [] })).filter(group => group.items.length)
})
async function refresh(id = run.value?.id) {
  if (!id) return
  run.value = await agentApi.planning.get(id)
  clearTimeout(timer)
  if (['queued', 'running'].includes(run.value.status)) timer = setTimeout(() => refresh(id), 1500)
}
async function submit() {
  const text = request.value.trim()
  if (!text || busy.value) return
  busy.value = true
  try { run.value = await agentApi.planning.create(text); request.value = ''; await refresh(run.value.id) }
  catch (e) { notify('画图分析失败：' + e.message) }
  finally { busy.value = false }
}
async function confirm() {
  if (!run.value || busy.value) return
  busy.value = true
  try { run.value = await agentApi.planning.confirm(run.value.id); await project.loadAll(); notify('画图方案已执行，四视图数据已刷新') }
  catch (e) { await refresh(run.value.id); notify('执行失败：' + e.message) }
  finally { busy.value = false }
}
async function cancel() {
  if (!run.value || busy.value) return
  busy.value = true
  try { run.value = await agentApi.planning.cancel(run.value.id); notify('已取消规划，未修改项目数据') }
  catch (e) { await refresh(run.value.id); notify(e.message) }
  finally { busy.value = false }
}
onUnmounted(() => clearTimeout(timer))
</script>
<template>
  <section class="planning-agent-panel" aria-labelledby="planning-agent-title">
    <div class="planning-agent-heading">
      <div><div class="h-sec" id="planning-agent-title">画图智能体 · Drawing Agent</div><p class="small">读取四视图背后的真实项目数据，先生成修改计划，确认后才执行。</p></div>
      <span class="planning-agent-badge">真实数据 · 人工确认</span>
    </div>
    <form class="planning-agent-form" @submit.prevent="submit">
      <textarea v-model="request" rows="2" maxlength="2000" placeholder="例如：把 T08 改给孙秋实；T08 现在是谁负责？" aria-label="画图智能体请求"></textarea>
      <button class="primary" type="submit" :disabled="busy">{{ busy ? '分析中…' : '生成规划' }}</button>
    </form>
    <div v-if="run" class="planning-agent-run">
      <div class="planning-run-head"><b>{{ statusText[run.status] || run.status }}</b><span class="small">{{ run.request_text }}</span></div>
      <div v-if="eventSteps.length" class="planning-steps" aria-label="Agent 工作进度">
        <span v-for="step in eventSteps" :key="step.kind + step.message" :class="{ failed: step.kind === 'failed' }">{{ step.label }}</span>
      </div>
      <p v-if="run.error_message" class="planning-error" role="alert">{{ run.error_message }}</p>
      <template v-if="result">
        <div class="planning-plan">
          <div class="planning-plan-title"><b>{{ result.summary }}</b><span class="small">{{ actionCount }} 项修改</span></div>
          <p v-if="result.answer">{{ result.answer }}</p>
          <div v-for="(a, index) in result.actions" :key="a.task_id || a.story_id || a.milestone_id || index" class="planning-action">
            <b>{{ a.task_id || a.story_id || a.milestone_id }}</b>
            <template v-if="a.type === 'UPDATE_TASK_OWNER'"> · 负责人 {{ a.from_owner_name || a.from_owner_id }} → {{ a.to_owner_name || a.to_owner_id }}</template>
            <template v-else-if="a.type === 'UPDATE_TASK_SCHEDULE'"> · 排期 W{{ a.start_week }}-W{{ a.end_week }}</template>
            <template v-else-if="a.type === 'UPDATE_STORY_SPRINT'"> · Sprint {{ a.sprint }}</template>
            <template v-else-if="a.type === 'UPDATE_TASK_DEPENDENCY'"> · 依赖 {{ a.before_dependencies || '无' }} → {{ a.after_dependencies || '无' }}</template>
            <template v-else-if="a.type === 'UPDATE_TASK_PRIORITY'"> · 优先级 {{ a.before_priority }} → {{ a.after_priority }}</template>
            <template v-else-if="a.type === 'CREATE_MILESTONE'"> · 新建里程碑 {{ a.name }} · W{{ a.week }} · 关联 {{ a.related_task_ids || '无' }}</template>
            <template v-else-if="a.type === 'UPDATE_MILESTONE'"> · 里程碑 {{ a.after_name || a.name }} · W{{ a.after_week || a.week }} · 关联 {{ a.after_related_task_ids || '无' }}</template>
          </div>
        </div>
        <div v-if="result.impacts?.length" class="planning-impacts"><b>影响对象</b><div v-for="(impact, index) in result.impacts" :key="index"><strong>{{ impact.entity }}</strong> · {{ impact.before }} → {{ impact.after }}<span v-if="impact.detail"> · {{ impact.detail }}</span></div></div>
        <div v-if="impactGroups.length" class="planning-impact-report"><b>影响分析</b><section v-for="group in impactGroups" :key="group.key"><strong>{{ group.title }}</strong><div v-for="item in group.items" :key="item.message">{{ item.message }}</div></section></div>
        <div v-if="result.warnings?.length" class="planning-warnings"><b>风险提示</b><div v-for="warning in result.warnings" :key="warning">{{ warning }}</div></div>
        <div v-if="run.status === 'waiting_confirmation' && !session.isViewer" class="planning-actions"><button type="button" :disabled="busy" @click="cancel">取消规划</button><button class="primary" type="button" :disabled="busy" @click="confirm">确认执行</button></div>
        <p v-else-if="run.status === 'waiting_confirmation' && session.isViewer" class="small">当前账号为只读角色，仅可查看规划结果，不能确认执行。</p>
      </template>
    </div>
  </section>
</template>
