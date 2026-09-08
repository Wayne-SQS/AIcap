<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useReviewStore } from '@/stores/review'
import { useMeetingStore } from '@/stores/meeting'
import { useProjectStore } from '@/stores/project'
import { useToast } from '@/composables/useToast'
import ReviewEditDialog from '@/components/review/ReviewEditDialog.vue'

/* AI 建议审核中心:离线=本地演示建议(decideSug);在线=服务器建议三键审核
   对齐旧版 renderReview(L1160-1168) + meeting-review.js renderReview(L88-113)
   + meeting-improvements.js 修改后采纳(L27-41) */
const review = useReviewStore()
const meeting = useMeetingStore()
const project = useProjectStore()
const router = useRouter()
const { notify } = useToast()
const editRef = ref(null)

const statusMap = { pending: '待审核', approved: '已采纳', rejected: '已拒绝', modified: '已修改' }
const serverStatusName = { pending: '待审核', approved: '已采纳', rejected: '已拒绝' }
const online = computed(() => meeting.online)
const decisions = computed(() => review.decisions.slice().reverse())
const serverDecisions = computed(() => meeting.suggestions.filter(s => s.reviewed_at))

/* bootstrap 完成后 online 才为 true:watch 而非 onMounted,避免挂载早于会话初始化漏加载 */
watch(online, v => {
  if (v) meeting.loadAll().catch(() => { /* 可手动刷新 */ })
}, { immediate: true })

async function refresh() {
  try { await meeting.loadAll(); await project.loadAll() }
  catch (err) { notify('刷新失败：' + err.message) }
}

/* 离线演示:三键决策 + prompt 理由 */
function decide(id, act) {
  const reason = prompt('决策理由（留空则记为已确认）：')
  if (reason === null) return
  const actTxt = review.decide(id, act, reason)
  if (actTxt) notify(`${id} 已${actTxt}`)
}

/* 在线:采纳/拒绝(prompt 理由,取消则不审核) */
async function serverDecide(id, decision) {
  if (meeting.busy.includes(id)) return
  const reason = prompt('审核理由（可留空；取消则不审核）：')
  if (reason === null) return
  meeting.busy.push(id)
  try {
    const result = await meeting.review(id, decision, reason)
    notify(result.status === 'approved' ? '已采纳并创建需求 ' + result.pool_item_id : '已拒绝，未改变业务数据')
    try { await project.loadAll() } catch (err) { notify('审核已保存，但刷新失败：' + err.message) }
  } catch (err) { notify('审核未确认，请刷新核对后重试：' + err.message) }
  finally {
    meeting.busy = meeting.busy.filter(x => x !== id)
  }
}

/* 在线:修改后采纳(弹窗表单) */
async function serverEdit(id) {
  if (meeting.busy.includes(id)) return
  const s = meeting.suggestions.find(x => x.id === id)
  if (!s) return
  const values = await editRef.value.open(s)
  if (!values) return
  meeting.busy.push(id)
  try {
    await meeting.review(id, null, values.reason, values.changes)
    notify('修改已采纳，原始建议已保留；创建需求 ' + '（见需求池）')
    try { await project.loadAll() } catch (err) { notify('审核已保存，刷新失败：' + err.message) }
  } catch (err) { notify('审核未确认，请刷新核对后重试：' + err.message) }
  finally {
    meeting.busy = meeting.busy.filter(x => x !== id)
    editRef.value?.finish()
  }
}

function openMeeting(id) {
  meeting.select(id)
  router.push({ name: 'ai' })
}
</script>

<template>
  <section class="view" id="view-review">
    <div class="hero">
      <div>
        <div class="eyebrow">AI REVIEW / 人在回路</div>
        <h1>AI 建议审核中心</h1>
        <p>集中查看 AI 提出的项目变更，采纳、修改或拒绝 —— 每条建议保留来源、证据与影响对象。</p>
      </div>
    </div>

    <!-- 离线:本地演示建议 -->
    <div v-if="!online" id="sug-list">
      <div v-for="s in review.suggestions" :key="s.id" class="sug-card">
        <div class="sug-head">
          <span class="sug-agent" :class="s.kind">{{ s.kind === 'meeting' ? '🎙' : '🚀' }} {{ s.agent }}</span>
          <span class="sug-status" :class="s.status">{{ statusMap[s.status] || '待审核' }}</span>
          <span class="small mono" style="margin-left:auto">{{ s.id }} · {{ s.time }}</span>
        </div>
        <div class="sug-row"><b>证据</b><span>{{ s.evidence }}</span></div>
        <div class="sug-row"><b>影响对象</b><span>{{ s.affected }}</span></div>
        <div class="sug-row"><b>建议说明</b><span>{{ s.note || '' }}</span></div>
        <div class="sug-diff">
          <template v-for="(c, i) in s.change || []" :key="i"><br v-if="i" /><span :class="c.d ? 'del' : 'add'">{{ c.d ? '− ' : '＋ ' }}{{ c.t }}</span></template>
        </div>
        <div v-if="s.status === 'pending'" class="sug-acts">
          <button class="approve" :data-sug="s.id" data-act="approved" @click="decide(s.id, 'approved')">✓ 采纳</button>
          <button :data-sug="s.id" data-act="modified" @click="decide(s.id, 'modified')">✎ 修改后采纳</button>
          <button class="reject" :data-sug="s.id" data-act="rejected" @click="decide(s.id, 'rejected')">✕ 拒绝</button>
        </div>
      </div>
      <div v-if="!review.suggestions.length" class="empty">暂无 AI 建议</div>
    </div>

    <!-- 在线:服务器建议 -->
    <div v-else id="sug-list">
      <p class="pool-note">真实会议建议 · 本轮仅支持新增需求池条目。{{ meeting.mayReview ? '你可以采纳、修改后采纳或拒绝。行动项与协调事项请查看对应会议的完整分析。' : '仅管理员或负责人可以审核。' }}</p>
      <button id="review-refresh" @click="refresh">刷新建议</button>
      <div v-for="s in meeting.suggestions" :key="s.id" class="sug-card" :data-meeting-suggestion="s.id">
        <div class="sug-head">
          <b>{{ s.agent_run_id ? '会议 Agent · 运行已记录' : s.origin === 'manual' ? '手动录入' : '外部 Agent（提交方标记）' }}</b>
          <span class="sug-status" :class="s.status">{{ serverStatusName[s.status] }}</span>
          <span>{{ s.id }}</span>
        </div>
        <div class="sug-row"><b>会议</b><span>{{ s.meeting_title }}</span></div>
        <div class="sug-row"><b>原文证据</b><span>{{ s.evidence }}</span></div>
        <div class="sug-row"><b>新增需求</b><span>{{ s.changes.title }} · {{ s.changes.priority }}</span></div>
        <div class="sug-row"><b>描述</b><span>{{ s.changes.description }}</span></div>
        <div class="sug-row"><b>说明</b><span>{{ s.note }}</span></div>
        <div v-if="s.approved_changes" class="sug-row"><b>最终采纳内容</b><span>{{ s.approved_changes.title }} · {{ s.approved_changes.priority }}<br>{{ s.approved_changes.description }}</span></div>
        <button :data-open-meeting="s.meeting_id" @click="openMeeting(s.meeting_id)">查看会议完整分析与待跟进事项</button>
        <div class="sug-row"><b>执行结果</b><span>{{ s.pool_item_id ? '已创建需求 ' + s.pool_item_id + '（后续可能移入看板或删除）' : s.status === 'rejected' ? '未执行，业务数据未改变' : '未执行' }}</span></div>
        <div v-if="s.status === 'pending' && meeting.mayReview" class="sug-acts">
          <button :data-edit-suggestion="s.id" :disabled="meeting.busy.includes(s.id)" @click="serverEdit(s.id)">修改后采纳</button>
          <button :data-meeting-review="s.id" data-decision="approve" :disabled="meeting.busy.includes(s.id)" @click="serverDecide(s.id, 'approve')">采纳</button>
          <button :data-meeting-review="s.id" data-decision="reject" :disabled="meeting.busy.includes(s.id)" @click="serverDecide(s.id, 'reject')">拒绝</button>
        </div>
      </div>
      <div v-if="!meeting.suggestions.length" class="empty">暂无待审建议，请到 AI 助手保存会议并录入建议。</div>
    </div>

    <details class="decision">
      <summary>{{ online ? '📋 决策留痕（服务器）' : '📋 决策留痕（本机演示）' }}</summary>
      <div class="logbody" id="decision-log">
        <template v-if="online">
          <div v-for="s in serverDecisions" :key="s.id" class="logrow">
            <time>{{ s.reviewed_at }} UTC</time><b>{{ s.id }}</b>
            <span>审核人 #{{ s.reviewed_by }} · {{ serverStatusName[s.status] }} · {{ s.reason || '未填写理由' }}{{ s.pool_item_id ? ' · ' + s.pool_item_id : '' }}</span>
          </div>
          <div v-if="!serverDecisions.length" class="empty">暂无服务器审核记录</div>
        </template>
        <template v-else>
          <div v-for="(d, i) in decisions" :key="i" class="logrow"><time>{{ d.t }}</time><b>{{ d.id }}</b><span>{{ d.act }}{{ d.reason ? ' · ' + d.reason : '' }}</span></div>
          <div v-if="!decisions.length" class="empty">暂无决策记录（采纳 / 修改 / 拒绝后自动记录）</div>
        </template>
      </div>
    </details>
    <ReviewEditDialog ref="editRef" />
  </section>
</template>
