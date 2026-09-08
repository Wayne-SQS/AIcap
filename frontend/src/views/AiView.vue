<script setup>
import { watch } from 'vue'
import { useMeetingStore } from '@/stores/meeting'
import { AIS } from '@/data/seed'
import MeetingPanel from '@/components/ai/MeetingPanel.vue'
import SubmitAgentPanel from '@/components/ai/SubmitAgentPanel.vue'
import ChatBox from '@/components/ai/ChatBox.vue'

/* AI 助手视图:结构对齐旧版 L596-631,逻辑对齐 renderAI(L1092-1103)
   会议智能体(离线演示/在线审核) + 任务提交智能体 + 对话演示 */
const meeting = useMeetingStore()

/* bootstrap 完成后 online 才为 true:watch 保证登录态就绪后拉取会议数据 */
watch(() => meeting.online, v => {
  if (v) meeting.loadAll().catch(() => { /* 网络异常时面板呈现空态,可手动刷新 */ })
}, { immediate: true })
</script>

<template>
  <section class="view" id="view-ai">
    <div class="hero">
      <div>
        <div class="eyebrow">AI COPILOT / 智能体协作</div>
        <h1>AI 助手</h1>
        <p>会议智能体 · 任务提交智能体 · 六项能力 —— AI 提出可追溯的建议，人验证依据、决定采纳并承担结果责任。</p>
      </div>
    </div>
    <div class="ai-grid" id="ai-grid">
      <div v-for="a in AIS" :key="a.name" class="aicard"><span class="ic">{{ a.ic }}</span><h3>{{ a.name }}</h3><p>{{ a.desc }}</p></div>
    </div>

    <div class="h-sec">会议智能体 · Meeting Agent</div>
    <div id="meeting">
      <MeetingPanel />
    </div>

    <div class="h-sec">任务提交智能体 · Commit Agent</div>
    <div id="submit-agent">
      <SubmitAgentPanel />
    </div>

    <div class="h-sec">对话演示</div>
    <div class="ai-chat">
      <ChatBox />
      <div class="prompt-list">
        <h4>试试这些问题</h4>
        <div v-for="p in ['帮我拆解一个用户故事', '评估本周团队负载', '当前项目的风险点', '生成 Sprint 2 排期建议']" :key="p" class="prompt">{{ p }}</div>
        <p class="small" style="margin:10px 0 0">演示回复为静态文案，实际接入需配置模型。</p>
      </div>
    </div>
  </section>
</template>
