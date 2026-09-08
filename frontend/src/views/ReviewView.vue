<script setup>
import { computed } from 'vue'
import { useReviewStore } from '@/stores/review'
import { useToast } from '@/composables/useToast'

/* AI 建议审核中心:结构对齐旧版 L657-667,逻辑对齐 renderReview(L1160-1168)/
   decideSug(L1169-1179):三键决策 + prompt 理由 + 决策留痕 */
const review = useReviewStore()
const { notify } = useToast()

const statusMap = { pending: '待审核', approved: '已采纳', rejected: '已拒绝', modified: '已修改' }
const decisions = computed(() => review.decisions.slice().reverse())

function decide(id, act) {
  const reason = prompt('决策理由（留空则记为已确认）：')
  if (reason === null) return  // 用户取消 prompt
  const actTxt = review.decide(id, act, reason)
  if (actTxt) notify(`${id} 已${actTxt}`)
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
    <div id="sug-list">
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
    <details class="decision">
      <summary>📋 决策留痕（本机）</summary>
      <div class="logbody" id="decision-log">
        <div v-for="(d, i) in decisions" :key="i" class="logrow"><time>{{ d.t }}</time><b>{{ d.id }}</b><span>{{ d.act }}{{ d.reason ? ' · ' + d.reason : '' }}</span></div>
        <div v-if="!decisions.length" class="empty">暂无决策记录（采纳 / 修改 / 拒绝后自动记录）</div>
      </div>
    </details>
  </section>
</template>
