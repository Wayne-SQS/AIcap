<script setup>
import { useSupervisorStore } from '@/stores/supervisor'
import SupervisorConversation from '@/components/SupervisorConversation.vue'

/* 主管浮窗:只是一个观察窗 —— 开合门 + 头部。
   正文(消息流/时间轴/输入框)在 SupervisorConversation,SSE 流由 store 常驻持有,
   收起不 abort:AI 办公室左栏挂着同一份对话,事件继续进时间轴。 */
const sup = useSupervisorStore()
function collapse() { sup.open = false }
</script>

<template>
  <section v-if="sup.open" id="supervisor-chat" class="sup-chat" aria-label="本 · 项目主管对话">
    <header class="sup-head">
      <span class="sup-headmark" aria-hidden="true">本</span>
      <div class="sup-headtext">
        <strong>本</strong>
        <small>{{ sup.statusText }}</small>
      </div>
      <button class="sup-icon" type="button" title="换个话题(新建会话)" @click="sup.resetSession()">＋</button>
      <button class="sup-icon" type="button" title="收起" @click="collapse()">×</button>
    </header>

    <SupervisorConversation />
  </section>
</template>
