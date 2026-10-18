<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useSupervisorStore } from '@/stores/supervisor'
import { useSessionStore } from '@/stores/session'

/* 主管对话的正文(无头部、无开合门):消息流 + 过程时间轴 + 输入框。
   供两个观察窗复用同一份 store 状态:
   1) 右下角浮窗(SupervisorChat)
   2) AI 办公室左栏(OfficeView)
   两处同时挂载也安全 —— 消息/时间轴/健康态全在 useSupervisorStore 里,
   SSE 流也由 store 常驻持有,组件卸载不断流。 */
const sup = useSupervisorStore()
const session = useSessionStore()
const draft = ref('')
const bodyEl = ref(null)

const suggestions = ['现在有几个任务在推进？', '谁手上的活最重？', '帮我看看项目整体进度']

const placeholders = computed(() => {
  if (!sup.health.reachable) return '主管未连接,发不出去'
  if (!sup.health.java) return '平台后端不可达'
  if (!sup.health.llmConfigured) return '服务端未配置模型密钥'
  if (sup.running) return '正在处理…'
  return '问一句,或让它派活给同事'
})

function stepsFor(runId) {
  if (!runId) return []
  return sup.timeline.filter(t => t.runId === runId && t.label !== '开始')
}

function submit() {
  const text = draft.value.trim()
  if (!text || sup.running) return
  draft.value = ''
  sup.send(text)
}
function pick(text) {
  if (sup.running) return
  sup.send(text)
}

/* 挂载即探活 + 补流:办公室里对话是常驻的,不能等用户发送才连;
   两者都幂等,浮窗同时存在也不会重复开流。 */
onMounted(() => { sup.probe(); sup.ensureStream() })

watch(
  () => [sup.messages.length, sup.timeline.length],
  async () => {
    await nextTick()
    if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
  }
)
</script>

<template>
  <div v-if="!session.online" class="sup-note">当前处于离线演示模式,登录后才可与「本」对话。</div>
  <div v-else-if="sup.notice" class="sup-note is-err">{{ sup.notice }}</div>

  <div ref="bodyEl" class="sup-body">
    <p v-if="!sup.messages.length" class="sup-empty small">
      我是<b>本</b>,这个项目的主管。三个同事 —— 译(会议)、衡(画像)、绘(画图)归我调度。
      <br>问数据我亲自去查,要动手的活我派给对应同事,产出会先送到你的审批队列。
    </p>

    <template v-for="m in sup.messages" :key="m.id">
      <ol v-if="stepsFor(m.runId).length" class="sup-steps">
        <li v-for="s in stepsFor(m.runId)" :key="s.seq" :class="'is-' + s.tone">
          <span class="sup-stepdot" aria-hidden="true"></span>
          <span class="sup-steplabel">{{ s.label }}</span>
          <span class="sup-steptext">{{ s.text }}</span>
          <span v-if="s.detail" class="sup-stepdetail">{{ s.detail }}</span>
        </li>
      </ol>

      <div class="sup-msg" :class="m.role === 'user' ? 'is-user' : 'is-agent'">
        <span v-if="m.role === 'agent'" class="sup-msgtag">本</span>
        <div class="sup-msgtext">{{ m.text }}</div>
        <div v-if="m.options && m.options.length" class="sup-opts">
          <button v-for="o in m.options" :key="o" type="button" @click="pick(o)">{{ o }}</button>
        </div>
      </div>
    </template>

    <div v-if="sup.running && !sup.timeline.length" class="sup-msg is-agent">
      <span class="sup-msgtag">本</span>
      <div class="sup-msgtext">正在接手…</div>
    </div>
  </div>

  <div v-if="sup.canSend && sup.messages.length < 3" class="sup-suggest">
    <button v-for="s in suggestions" :key="s" type="button" @click="pick(s)">{{ s }}</button>
  </div>

  <footer class="sup-foot">
    <input
      v-model="draft"
      :placeholder="placeholders"
      :disabled="!sup.canSend"
      aria-label="向本提问"
      @keydown.enter.prevent="submit()"
    >
    <button class="primary" type="button" :disabled="!sup.canSend || !draft.trim()" @click="submit()">发送</button>
  </footer>
</template>
