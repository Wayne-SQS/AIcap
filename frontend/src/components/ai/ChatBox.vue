<script setup>
import { onMounted, ref, nextTick } from 'vue'
import { CHAT_KB } from '@/data/seed'

/* 对话演示:静态 KB 关键词匹配 + 打字机效果,对齐旧版 renderAI(L1092-1124) */
const body = ref(null)
const input = ref('')
const msgs = ref([])
const typing = ref(false)

function append(m) {
  msgs.value.push(m)
  nextTick(() => { if (body.value) body.value.scrollTop = body.value.scrollHeight })
}
function send(text) {
  text = (text || '').trim()
  if (!text || typing.value) return
  append({ u: true, t: text, shown: text })
  input.value = ''
  const hit = CHAT_KB.find(k => text.includes(k.kw))
  const full = hit ? hit.reply : '已收到。这是一个演示原型：真实版会基于项目数据（故事/任务/成员负载）给出可追溯的分析，并由你确认后再入库。'
  const m = { u: false, t: full, shown: '' }
  append(m)
  typing.value = true
  let i = 0
  const ty = setInterval(() => {
    i++
    m.shown = full.slice(0, i)
    nextTick(() => { if (body.value) body.value.scrollTop = body.value.scrollHeight })
    if (i >= full.length) { clearInterval(ty); typing.value = false }
  }, 16)
}

onMounted(() => {
  const boot = [
    { u: false, t: '你好，我是爱管理的需求分析智能体。可以帮你拆解需求、评估负载、识别风险或排期。' },
    { u: true, t: '帮我看看当前项目进度' },
    { u: false, t: '当前 Sprint 1 已完成 2 / 9 个故事（22%），进行中 3 个。整体 20 条故事覆盖 5 条骨干活动、3 个 Sprint。建议先推进 M05 故事地图与 M06 任务拆分。' }
  ]
  boot.forEach((m, i) => setTimeout(() => append({ ...m, shown: m.t }), i * 550))
})
</script>

<template>
  <div class="chatbox">
    <div class="chathead"><span class="dot"></span>爱管理 · 需求分析智能体</div>
    <div class="chatbody" id="chatbody" ref="body">
      <div v-for="(m, i) in msgs" :key="i" class="msg" :class="m.u ? 'user' : 'bot'">
        <template v-if="m.u">{{ m.shown }}</template>
        <template v-else><span class="tag">需求分析智能体</span>{{ m.shown }}<span v-if="m.shown.length < m.t.length" class="cursor">▌</span></template>
      </div>
    </div>
    <div class="chatfoot">
      <input id="chatinput" v-model="input" placeholder="例如：帮我拆解「成员任务图」…" aria-label="输入问题" @keydown.enter="send(input)">
      <button class="primary" id="chatsend" @click="send(input)">发送</button>
    </div>
  </div>
</template>
