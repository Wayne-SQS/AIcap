<script setup>
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'

/* 会议 Agent 运行面板:对齐 meeting-agent.js renderAgentPanel/refreshAgentRun/startAgentRun
   轮询 1.5s,generation 计数防切换会议后的过期渲染;组件卸载即停表 */
const meeting = useMeetingStore()
const router = useRouter()
const { notify } = useToast()

let timer = null
let generation = 0
const panelEl = ref(null)

const labels = { queued: '排队中', running: '正在分析', awaiting_review: '分析完成 · 建议已送审', completed: '分析完成 · 没有新增建议', failed: '分析失败' }
const run = computed(() => meeting.agentRun)
const config = computed(() => meeting.agentConfig)
const configured = computed(() => config.value?.configured && config.value?.worker_enabled)
const result = computed(() => run.value?.result)

async function refreshRun() {
  try { await meeting.refreshRun() }
  catch (err) { meeting.agentError = err.message }
  schedule()
}
function schedule() {
  clearTimeout(timer)
  if (meeting.runActive && panelEl.value?.isConnected) {
    const g = generation
    timer = setTimeout(async () => {
      if (g !== generation || !panelEl.value?.isConnected) return
      await refreshRun()
    }, 1500)
  }
}

async function start(retryId = null) {
  try {
    await meeting.startRun(retryId)
    await refreshRun()
  } catch (err) {
    meeting.agentError = '未能启动分析：' + err.message
  }
}

async function reanalyze() {
  try {
    await meeting.reanalyze()
    await start()
  } catch (err) { meeting.agentError = '重新分析失败：' + err.message }
}

function facts(items) {
  return (items || []).map(item => ({
    text: item.text || item.description,
    owner: Object.hasOwn(item, 'owner_mention') ? (item.owner_mention || '待确认') : null,
    deadline: Object.hasOwn(item, 'owner_mention') ? (item.deadline_text || '待确认') : null,
    seg: item.evidence.segment_id, quote: item.evidence.quote
  }))
}

onMounted(() => { generation++; refreshRun() })
onUnmounted(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section id="meeting-agent-panel" ref="panelEl" :data-meeting-id="meeting.selected">
    <div class="h-sec">会议 Agent</div>
    <p class="small">{{ configured ? '将会议转写和查询到的项目数据发送到已配置模型分析；生成的建议仍需人工审核。' : '服务端模型密钥尚未配置或工作线程未启用；可继续手动录入建议。' }}</p>
    <p id="agent-message">{{ meeting.agentError || (run ? `${labels[run.status]} · 尝试 ${run.attempt}` : '选择已保存会议后可开始分析') }}</p>
    <p v-if="run?.error_message" role="alert">{{ run.error_message }}</p>
    <button id="agent-start" :disabled="!configured || !meeting.maySubmit || !meeting.selected || !!run || meeting.agentLoading" @click="start()">分析会议</button>
    <button v-if="run?.status === 'failed'" id="agent-retry" :disabled="!configured || !meeting.maySubmit || meeting.agentLoading" @click="start(run.id)">重试分析</button>
    <button v-if="run" id="agent-refresh" @click="refreshRun">刷新结果</button>
    <button v-if="result && meeting.maySubmit" id="agent-reanalyze" @click="reanalyze">复制会议并重新分析</button>

    <template v-if="result">
      <div class="sum-grid">
        <div class="sum-card"><h4>摘要</h4><p>{{ result.summary }}</p></div>
        <div class="sum-card"><h4>决议</h4><ul><li v-for="(f, i) in facts(result.decisions)" :key="'d' + i">{{ f.text }}<br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
        <div class="sum-card"><h4>行动项 · 待人工跟进</h4><ul><li v-for="(f, i) in facts(result.action_items)" :key="'a' + i">{{ f.text }}<span v-if="f.owner"> · 负责人：{{ f.owner }} · 日期：{{ f.deadline }}</span><br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
        <div class="sum-card"><h4>协调事项 · 待负责人确认</h4><ul><li v-for="(f, i) in facts(result.coordination_items)" :key="'c' + i">{{ f.text }}<span v-if="f.owner"> · 负责人：{{ f.owner }} · 日期：{{ f.deadline }}</span><br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
        <div class="sum-card"><h4>状态与验收约束</h4><ul><li v-for="(f, i) in facts(result.status_constraints)" :key="'s' + i">{{ f.text }}<span v-if="f.owner"> · 负责人：{{ f.owner }} · 日期：{{ f.deadline }}</span><br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
        <div class="sum-card"><h4>其他会议事实</h4><ul><li v-for="(f, i) in facts(result.source_notes)" :key="'n' + i">{{ f.text }}<br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
        <div class="sum-card"><h4>风险 / 待确认推断</h4><ul><li v-for="(f, i) in facts(result.risks)" :key="'r' + i">{{ f.text }}<br><span class="small">依据 {{ f.seg }}：{{ f.quote }}</span></li></ul></div>
      </div>
      <p class="small">以上行动项和协调事项已记录在会议分析中，尚未创建或修改项目任务；负责人需确认后在项目侧跟进。</p>
      <h4>未决问题与数据限制</h4>
      <ul><li v-for="(t, i) in [...result.unresolved_questions, ...result.limitations]" :key="'u' + i">{{ t }}</li></ul>
      <p>已保存 {{ (result.suggestion_ids || []).length }} 条建议；审核和执行状态请查看审核中心。</p>
      <p v-for="p in result.skipped_proposals || []" :key="p.title">未重复创建：{{ p.title }}</p>
      <button v-if="meeting.mayReview || meeting.maySubmit" id="agent-go-review" @click="router.push({ name: 'review' })">查看审核中心</button>
    </template>

    <details v-if="run?.events">
      <summary>运行记录 · {{ run.model }} · {{ run.prompt_version }}</summary>
      <div v-for="(e, i) in run.events" :key="i" class="logrow">
        <b>第 {{ e.attempt }} 次 · {{ e.kind }}</b>
        <pre style="white-space:pre-wrap;max-height:200px;overflow:auto">{{ JSON.stringify(e.detail, null, 2) }}</pre>
      </div>
    </details>
  </section>
</template>
