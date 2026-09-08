<script setup>
import { useMeetingStore } from '@/stores/meeting'
import { useToast } from '@/composables/useToast'
import { useReviewStore } from '@/stores/review'
import { SUG_KEY } from '@/constants'

/* 任务提交智能体:演示为样例数据;在线时生成按钮禁用(对齐 meeting-review.js L134-141)
   离线生成建议送审对齐旧版 renderSubmitAgent(L1239-1248) */
const meeting = useMeetingStore()
const review = useReviewStore()
const { notify } = useToast()

const commits = [
  ['a363255', '成员1', '部署爱管理像素风团队工作台网站', 'T15'],
  ['7c2d73c', '成员2', '初始化仓库与 README', 'T01'],
  ['f0e1d2c', '成员4', 'UML 用例图渲染修复', 'T08'],
  ['b3a4f5e', '成员4', '时序图消息箭头优化', 'T08'],
  ['c9d8e7f', '成员1', '看板拖拽状态持久化', 'T04']
]

function genDemoSuggestion() {
  review.suggestions.unshift({
    id: 'SG0' + (review.suggestions.length + 1), agent: '任务提交智能体', kind: 'submit',
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    evidence: '成员3 近 3 天无 commit 且 T07 未关闭', affected: 'T07 · 成员3',
    change: [{ t: '建议确认阻塞或重新分配', d: false }],
    note: '项目层面的协调建议，人员调整仍需人工确认。', status: 'pending'
  })
  try { localStorage.setItem(SUG_KEY, JSON.stringify(review.suggestions)) } catch { /* ignore */ }
  notify('已生成协调建议，前往「AI 审核中心」处理')
}
</script>

<template>
  <p class="pool-note">任务提交智能体读取 GitHub commit / PR / review 事件（演示为样例数据）。结论区分事实与推断，不把次数等同于质量，也不作为人员奖惩依据。</p>
  <div class="h-sec" style="margin-top:0">最近提交</div>
  <div id="commits" style="margin-bottom:14px">
    <div v-for="c in commits" :key="c[0]" class="commit-row">
      <span class="sha">{{ c[0].slice(0, 7) }}</span><span class="msg">{{ c[2] }}</span>
      <span class="tag2">{{ c[3] }}</span><span class="small" style="flex:0 0 auto">{{ c[1] }}</span>
    </div>
  </div>
  <div class="sum-grid">
    <div class="sum-card"><h4>🧾 成员工作状态（事实）</h4><ul><li>成员3 近 3 天无可见提交</li><li>成员4 昨日 6 次提交集中在 T08</li><li>成员1 / 成员2 活动稳定</li></ul></div>
    <div class="sum-card"><h4>⚠ 推断与风险</h4><ul><li>成员3 可能被接口联调阻塞（待确认）</li><li>成员4 单点负载偏高</li></ul></div>
  </div>
  <button class="primary" id="submit-gen" :disabled="meeting.online" @click="genDemoSuggestion">
    {{ meeting.online ? '提交智能体为演示，尚未接入服务器审核' : '＋ 生成协调建议 → 送审' }}
  </button>
</template>
