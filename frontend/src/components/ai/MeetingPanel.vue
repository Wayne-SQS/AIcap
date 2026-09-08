<script setup>
import { reactive, ref } from 'vue'
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'
import { useReviewStore } from '@/stores/review'
import { SUG_KEY } from '@/constants'
import AgentRunPanel from './AgentRunPanel.vue'

/* 会议智能体面板:离线=演示转写+生成建议;在线=保存会议/选择/手动录入待审建议
   对齐旧版 renderMeeting(L1229-1237) + meeting-review.js renderMeeting(L21-86) */
const meeting = useMeetingStore()
const review = useReviewStore()
const { notify } = useToast()

const saveForm = reactive({ title: '', transcript: '' })
const saving = ref(false)
const proposal = reactive({ title: '', description: '', evidence: '', priority: 'Could', note: '' })
const proposing = ref(false)

const transcriptDemo = [
  ['00:12', '成员1', '好，Sprint 2 的重点大家都清楚吗？'],
  ['00:31', '成员2', '建议把 AI 拆解往后放，先做会议闭环。'],
  ['00:52', '成员3', '成员任务图先做负载热力图，绿格子后置。'],
  ['01:20', '成员4', '录音前要有知情同意，这个验收要写清楚。']
]

async function refresh() {
  try { await meeting.loadAll() } catch (err) { notify('刷新失败：' + err.message) }
}

async function saveMeeting() {
  saving.value = true
  let saved = false
  try {
    await meeting.saveMeeting({ title: saveForm.title, transcript: saveForm.transcript })
    saved = true
    saveForm.title = ''
    saveForm.transcript = ''
    notify('会议已保存到服务器')
  } catch (err) { notify('保存失败：' + err.message) }
  finally { if (!saved) saving.value = false; else saving.value = false }
}

async function submitProposal() {
  proposing.value = true
  let submitted = false
  try {
    await meeting.submitProposal(proposal)
    submitted = true
    proposal.title = ''
    proposal.description = ''
    proposal.evidence = ''
    proposal.note = ''
    notify('建议已保存，等待负责人审核；尚未创建需求')
  } catch (err) { notify('提交失败：' + err.message) }
  finally { proposing.value = false }
}

/* 离线演示:从会议生成建议送审(对齐旧版 #meeting-gen) */
function genDemoSuggestion() {
  review.suggestions.unshift({
    id: 'SG0' + (review.suggestions.length + 1), agent: '会议智能体', kind: 'meeting',
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    evidence: '会议决议 #3 / 转写 00:52', affected: '成员任务图 · 需求池',
    change: [{ t: '新增需求：贡献绿格子（US28）进入需求池', d: false }],
    note: '会议智能体根据转写生成，未经审核不改动正式数据。', status: 'pending'
  })
  try { localStorage.setItem(SUG_KEY, JSON.stringify(review.suggestions)) } catch { /* ignore */ }
  notify('已生成建议，前往「AI 审核中心」处理')
}
</script>

<template>
  <!-- 离线:演示模式 -->
  <div v-if="!meeting.online">
    <div class="rec">
      <div class="eyebrow" style="margin-bottom:8px">MEETING AGENT · Sprint 2 规划会</div>
      <div style="display:flex;align-items:center;gap:10px"><span class="rec-dot"></span><b>录音中 12:34</b><span class="small">已获参会者知情同意 · 实时转写</span></div>
    </div>
    <div class="transcript" id="meeting-transcript">
      <div v-for="l in transcriptDemo" :key="l[0]" class="tl"><b>{{ l[0] }}</b><span class="spk">{{ l[1] }}</span><span>{{ l[2] }}</span></div>
    </div>
    <div class="sum-grid">
      <div class="sum-card"><h4>📌 会议摘要</h4><ul><li>确认 Sprint 2 聚焦「会议闭环 + 审核中心」</li><li>AI PRD 拆解降级为 Should</li><li>成员任务图先做负载热力图</li></ul></div>
      <div class="sum-card"><h4>✅ 决议</h4><ul><li>#3 会议闭环优先于六项能力</li><li>#4 需求池承接未确认需求</li></ul></div>
      <div class="sum-card"><h4>🚩 行动项</h4><ul><li>成员2 整理会议转写（W3）</li><li>成员3 验证录音接口（W3）</li><li>成员4 复核隐私同意机制</li></ul></div>
      <div class="sum-card"><h4>❓ 遗留问题</h4><ul><li>是否要求说话人分离？待确认</li><li>录音用浏览器还是平台接入？</li></ul></div>
    </div>
    <button class="primary" id="meeting-gen" @click="genDemoSuggestion">＋ 从会议生成需求/排期建议 → 送审</button>
  </div>

  <!-- 在线:真实会议审核 -->
  <div v-else>
    <p class="pool-note">会议转写与建议 · 保存后可使用下方会议 Agent 分析，也可手动录入建议；所有建议经审核后执行。</p>
    <form id="meeting-save-form" @submit.prevent="saveMeeting">
      <label class="field">会议标题<input name="title" v-model="saveForm.title" required maxlength="200"></label>
      <label class="field">会议转写<textarea name="transcript" v-model="saveForm.transcript" required maxlength="16000" rows="5"></textarea></label>
      <button class="primary" type="submit" :disabled="!meeting.maySubmit || saving">保存会议</button>
    </form>
    <div class="h-sec">已保存会议</div>
    <select id="meeting-select" aria-label="已保存会议" :value="meeting.selected" @change="meeting.select($event.target.value)">
      <option value="">请选择会议</option>
      <option v-for="m in meeting.meetings" :key="m.id" :value="m.id">{{ m.title }}</option>
    </select>
    <button type="button" id="meeting-refresh" @click="refresh">刷新会议与建议</button>
    <pre id="saved-transcript" style="white-space:pre-wrap;max-height:240px;overflow:auto">{{ meeting.selectedMeeting?.transcript || '暂无会议，请先保存。' }}</pre>
    <AgentRunPanel />
    <form id="meeting-proposal-form" @submit.prevent="submitProposal">
      <div class="h-sec">手动录入待审建议 · 仅新增需求池条目</div>
      <label class="field">需求标题<input name="title" v-model="proposal.title" required maxlength="200"></label>
      <label class="field">需求描述<textarea name="description" v-model="proposal.description" maxlength="10000" rows="3"></textarea></label>
      <label class="field">会议证据（复制原文中的连续片段）<textarea name="evidence" v-model="proposal.evidence" required maxlength="5000" rows="2"></textarea></label>
      <label class="field">优先级（录入人选择，默认 Could）<select name="priority" v-model="proposal.priority"><option>Could</option><option>Should</option><option>Must</option></select></label>
      <label class="field">建议说明<input name="note" v-model="proposal.note" maxlength="2000"></label>
      <button class="primary" type="submit" :disabled="!meeting.selectedMeeting || !meeting.maySubmit || proposing">提交待审建议</button>
      <p class="small">负责人或管理员批准后才会创建需求；拒绝不改变项目数据。</p>
    </form>
  </div>
</template>
